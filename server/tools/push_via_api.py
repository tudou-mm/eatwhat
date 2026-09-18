#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
push_via_api.py —— 在「github.com:443 不可达、但 api.github.com 可达」的环境里推送提交。

背景
----
某些沙箱 / 代理环境会阻断 `github.com` 直连（`git push` 报 Connection reset 或
Failed to connect to github.com:443），但 REST API 所在的 `api.github.com`
是通的。此时 `git push` 无解，只能用 GitHub 的 **Git Data API** 手工重建对象：

    blobs → trees → commits → 更新 ref

本脚本逐提交（不是压成一个）重建，**作者 / 提交者 / 时间 / commit message
原样保留**，推完后远端历史与本地完全一致。

凭证
----
只从环境变量读，**绝不写盘、绝不硬编码**：

    export GITHUB_TOKEN=$(gh auth token)     # 或用 PAT，scope 需含 repo
    python server/tools/push_via_api.py

用法
----
    python server/tools/push_via_api.py                  # 推当前分支到同名远端分支
    python server/tools/push_via_api.py --dry-run        # 只看要推什么
    python server/tools/push_via_api.py --remote upstream --branch main

依赖：requests（venv 解释器已装）
"""

import argparse
import base64
import datetime
import os
import subprocess
import sys

try:
    import requests
except ImportError:
    sys.exit("需要 requests：请用 venv 解释器运行 "
             "(~/.workbuddy/binaries/python/envs/default/Scripts/python.exe)")

API = "https://api.github.com"


# --------------------------------------------------------------------------- #
# git 读取层
# --------------------------------------------------------------------------- #
def git(*args):
    """跑一条 git 命令，返回 stdout 的 bytes（不经 shell，避免转义问题）。"""
    r = subprocess.run(["git"] + list(args), capture_output=True)
    if r.returncode != 0:
        raise RuntimeError("git %s 失败: %s" % (" ".join(args),
                                               r.stderr.decode("utf-8", "ignore").strip()))
    return r.stdout


def git_text(*args):
    return git(*args).decode("utf-8", "ignore").strip()


def remote_to_owner_repo(url):
    """把 remote url 解析成 (owner, repo)，支持 https 与 ssh 两种写法。"""
    u = url.strip()
    if u.endswith(".git"):
        u = u[:-4]
    if u.startswith("git@"):                      # git@github.com:owner/repo
        u = u.split(":", 1)[1]
    elif "://" in u:                              # https://github.com/owner/repo
        u = u.split("://", 1)[1]
        u = u.split("/", 1)[1] if "/" in u else u
    parts = u.split("/")
    if len(parts) < 2:
        raise RuntimeError("无法从 remote url 解析 owner/repo：%s" % url)
    return parts[-2], parts[-1]


def resolve_remote_url(remote):
    return git_text("remote", "get-url", remote)


def changed_paths(sha):
    """
    返回 [(status, path), ...]，status ∈ A/M/D/R/C/T。

    ⚠️ 必须用 `-z`（NUL 分隔）。默认输出会把非 ASCII 路径 **C-quote** 成
    `"docs/05-\\345\\220..."` 这种带引号带反斜杠的转义串，照原样拿去
    `ls-tree` 会取不到对象 —— 后果是**静默漏掉所有中文名文件**，
    推上去的历史看着成功、内容却是残缺的。（踩过：第一版就是这样，
    远端 tree 和本地 tree sha 对不上才发现。）
    重命名（R）/ 复制（C）拆成 delete + add：tree API 不支持 rename，
    拆开效果等价且更不容易出错。
    """
    parent = git_text("rev-list", "--parents", "-n", "1", sha).split()
    args = ["diff-tree", "-r", "-z", "--no-commit-id", "--name-status"]
    if len(parent) == 1:                          # 根提交
        args += ["--root", sha]
    else:
        args += [parent[1], sha]

    toks = git(*args).split(b"\0")
    res = []
    i = 0
    while i < len(toks):
        st = toks[i].decode("utf-8", "surrogateescape")
        i += 1
        if not st:
            break
        if st[0] in ("R", "C"):
            old = toks[i].decode("utf-8", "surrogateescape")
            new = toks[i + 1].decode("utf-8", "surrogateescape")
            i += 2
            res.append(("D", old))
            res.append(("A", new))
        else:
            res.append((st[0], toks[i].decode("utf-8", "surrogateescape")))
            i += 1
    return res


def blob_sha_of(commit, path):
    """
    取某提交里某路径的 blob sha。
    ⚠️ 必须按提交取，不能读工作区（历史提交的内容与工作区不同）。
    用 `rev-parse <commit>:<path>` 而不是 `ls-tree` —— 参数不经 shell，
    中文/空格路径都是原样传入，不会被 quote。
    """
    try:
        return git_text("rev-parse", "%s:%s" % (commit, path))
    except RuntimeError:
        return None


def commit_meta(sha):
    """
    取 commit message + 作者/提交者身份与时间，**原样保留**。

    ⚠️ 这里踩过一个隐蔽的坑：commit sha 对 message 是**逐字节**敏感的。
    第一版用 `git log --format=%B` 再 `.strip()`，把 message 结尾那个
    `\\n` 吃掉了 —— 于是远端算出的 sha 永远和本地对不上（一个 1345 字节、
    一个 1344 字节，差的就这一个换行）。后果：远端历史看着对、但每个提交
    的 sha 都与本地不同，`git update-ref refs/remotes/...` 也写不进去，
    本地会一直误显示「领先 N 个提交」。
    所以这里直接读原始提交对象按 `\\n\\n` 切，不做任何 strip。
    """
    raw = git("cat-file", "commit", sha)
    head, _, msg = raw.partition(b"\n\n")
    text = head.decode("utf-8", "surrogateescape")

    author_line = committer_line = ""
    for line in text.split("\n"):
        if line.startswith("author "):
            author_line = line[7:]
        elif line.startswith("committer "):
            committer_line = line[10:]

    def parse_ident(s):
        """'NAME <EMAIL> EPOCH +TZ' → {'name','email','date'(ISO8601带时区)}"""
        name_email, _, ts = s.rpartition("> ")
        name, _, email = name_email.partition(" <")
        epoch, _, tz = ts.strip().partition(" ")
        off_min = int(tz[1:3]) * 60 + int(tz[3:5])
        if tz[0] == "-":
            off_min = -off_min
        dt = datetime.datetime.fromtimestamp(int(epoch),
                                            datetime.timezone(
                                                datetime.timedelta(minutes=off_min)))
        return {"name": name.strip(), "email": email.strip(),
                "date": dt.isoformat()}

    return {
        "author": parse_ident(author_line),
        "committer": parse_ident(committer_line),
        "message": msg.decode("utf-8", "surrogateescape"),
    }


def commit_tree(sha):
    return git_text("rev-parse", sha + "^{tree}")


# --------------------------------------------------------------------------- #
# API 层
# --------------------------------------------------------------------------- #
class Gh:
    def __init__(self, token):
        self.s = requests.Session()
        self.s.headers.update({
            "Authorization": "Bearer " + token,
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "eatwhat-push-via-api",
        })

    def _req(self, method, path, payload=None):
        url = path if path.startswith("http") else API + path
        r = self.s.request(method, url, json=payload, timeout=60)
        if r.status_code >= 400:
            detail = r.text[:400]
            try:
                detail = r.json().get("message", detail)
            except Exception:
                pass
            raise RuntimeError("HTTP %d %s %s → %s" % (r.status_code, method, url, detail))
        return r.json() if r.text else {}

    def repo(self, owner, name):
        return self._req("GET", "/repos/%s/%s" % (owner, name))

    def ref(self, owner, name, branch):
        return self._req("GET", "/repos/%s/%s/git/ref/heads/%s" % (owner, name, branch))

    def blob(self, owner, name, content_b64):
        return self._req("POST", "/repos/%s/%s/git/blobs" % (owner, name),
                         {"content": content_b64, "encoding": "base64"})

    def tree(self, owner, name, base_tree, entries):
        return self._req("POST", "/repos/%s/%s/git/trees" % (owner, name),
                         {"base_tree": base_tree, "tree": entries})

    def commit(self, owner, name, message, tree, parents, author, committer):
        return self._req("POST", "/repos/%s/%s/git/commits" % (owner, name), {
            "message": message, "tree": tree, "parents": parents,
            "author": author, "committer": committer,
        })

    def update_ref(self, owner, name, branch, sha, force=False):
        return self._req("PATCH", "/repos/%s/%s/git/refs/heads/%s" % (owner, name, branch),
                         {"sha": sha, "force": force})


# --------------------------------------------------------------------------- #
# 主流程
# --------------------------------------------------------------------------- #
def main():
    ap = argparse.ArgumentParser(description="用 GitHub Git Data API 推送提交")
    ap.add_argument("--remote", default="origin")
    ap.add_argument("--branch", default=None, help="默认取当前分支")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--force", action="store_true",
                    help="允许非快进更新远端分支（远端已跑偏时用）")
    args = ap.parse_args()

    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if not token:
        sys.exit("缺少凭证。请先 export GITHUB_TOKEN=$(gh auth token) "
                 "（或填一个 scope=repo 的 PAT）。注意：不要写进任何文件。")

    branch = args.branch or git_text("rev-parse", "--abbrev-ref", "HEAD")
    owner, repo = remote_to_owner_repo(resolve_remote_url(args.remote))
    print("[push] 仓库 %s/%s  分支 %s" % (owner, repo, branch))

    gh = Gh(token)

    # 1) 远端当前 head
    force_update = args.force
    try:
        remote_head = gh.ref(owner, repo, branch)["object"]["sha"]
        print("[push] 远端 %s = %s" % (branch, remote_head[:8]))
    except RuntimeError as e:
        if "404" in str(e) or "Not Found" in str(e):
            remote_head = None
            print("[push] 远端分支不存在，将新建")
        else:
            raise

    # 2) 待推提交（老的在前）
    rng = "%s..HEAD" % remote_head if remote_head else "HEAD"
    commits = []
    if remote_head:
        out = git_text("rev-list", "--reverse", rng)
        commits = [c for c in out.splitlines() if c.strip()]
    else:
        commits = git_text("rev-list", "--reverse", "HEAD").splitlines()

    if not commits:
        print("[push] 没有新提交，无需推送")
        return 0

    total_files = sum(len(changed_paths(c)) for c in commits)
    print("[push] %d 个提交 / %d 处文件改动" % (len(commits), total_files))
    for c in commits:
        print("       %s  %s" % (c[:8], git_text("log", "-1", "--format=%s", c)))

    if args.dry_run:
        print("[push] --dry-run，到此为止")
        return 0

    # 3) 远端基准 tree：本地 refs/remotes/<remote>/<branch> 可能过期，
    #    但远端 head 就是本地某个已有提交（快进场景），直接读那个提交的 tree。
    if remote_head:
        try:
            base_tree = commit_tree(remote_head)          # 本地对象库里可能就有
        except RuntimeError:
            base_tree = gh._req("GET", "/repos/%s/%s/git/commits/%s"
                                % (owner, repo, remote_head))["tree"]["sha"]
    else:
        base_tree = None

    parent_sha = remote_head
    new_head = remote_head

    for i, c in enumerate(commits, 1):
        meta = commit_meta(c)
        entries = []
        for status, path in changed_paths(c):
            if status == "D":
                # sha=None 表示从 base_tree 删掉该路径
                entries.append({"path": path, "mode": "100644",
                                "type": "blob", "sha": None})
            else:
                bsha = blob_sha_of(c, path)
                if bsha is None:
                    continue
                raw = git("cat-file", "blob", bsha)
                b64 = base64.b64encode(raw).decode("ascii")
                new_blob = gh.blob(owner, repo, b64)["sha"]
                entries.append({"path": path, "mode": "100644",
                                "type": "blob", "sha": new_blob})

        if not entries:
            # ⚠️ 空提交（无文件变化）也必须**照建**，不能 continue。
            #
            # 踩过：本脚本原本在这里直接跳过，既没建 commit、也没推进
            # `parent_sha`。后果是**后续提交挂到了错误的父节点上** ——
            # `a(有文件) → b(空) → c(有文件)` 推上去会变成 `a → c`，
            # b 被静默丢弃、c 的 parent 直接指向 a。
            # 症状：本地与远端 sha 对不上，且历史里少了一个提交。
            # （踩过：`docs: 补记 v1.9` 那个空提交就是这样丢的，
            #   导致末尾提交的 parent 从 bb7bacd 变成了 e61e191。）
            #
            # tree 用 `base_tree`（即父提交的 tree）—— 这正是「无变化」的含义。
            if base_tree is None:
                # 根提交且无文件 = 空仓库，没有 tree 可用，只能跳过
                print("[push] %d/%d %s 空根提交，跳过" % (i, len(commits), c[:8]))
                continue
            print("[push] %d/%d %s 无文件变化 → 建空提交（保持 parent 链完整）"
                  % (i, len(commits), c[:8]))
            cmt = gh.commit(owner, repo, meta["message"], base_tree,
                            [parent_sha] if parent_sha else [],
                            meta["author"], meta["committer"])
            parent_sha = cmt["sha"]
            new_head = cmt["sha"]
            # base_tree 不变（本来就没变化）
            same = "＝本地" if cmt["sha"] == c else "≠本地(sha 算法差异)"
            print("[push] %d/%d %s → %s  (空提交)  sha %s"
                  % (i, len(commits), c[:8], cmt["sha"][:8], same))
            continue

        tree_sha = gh.tree(owner, repo, base_tree, entries)["sha"]

        # ---- 硬校验：远端算出的 tree 必须和本地 tree 完全一致 ----
        # blob 内容是按字节原样上传的，GitHub 的 sha 算法也一样，
        # 所以只要「路径集合 + 内容 + 模式」都对，sha 必然相等。
        # 不相等说明漏了文件或模式错了 —— 这时候**绝不能**建 commit，
        # 否则远端会静默存下一棵残缺的树（比推失败糟糕得多）。
        local_tree = commit_tree(c)
        if tree_sha != local_tree:
            print("\n[push] ✗ 中止：tree 校验失败")
            print("       本地 tree %s" % local_tree)
            print("       远端 tree %s" % tree_sha)
            print("       提交     %s (%s)" % (c[:8], git_text("log", "-1", "--format=%s", c)))
            print("       本次带的路径：")
            for status, path in changed_paths(c):
                print("         %s %s" % (status, path))
            print("       提示：多半是路径解析漏了文件（非 ASCII 名需用 -z 解析）。")
            print("       远端 ref 尚未改动，可安全重试。")
            return 2

        cmt = gh.commit(owner, repo, meta["message"], tree_sha,
                        [parent_sha] if parent_sha else [],
                        meta["author"], meta["committer"])
        parent_sha = cmt["sha"]
        new_head = cmt["sha"]
        base_tree = tree_sha
        same = "＝本地" if cmt["sha"] == c else "≠本地!"
        print("[push] %d/%d %s → %s  (%d 文件)  sha %s"
              % (i, len(commits), c[:8], cmt["sha"][:8], len(entries), same))

    # 4) 更新分支引用
    if new_head == remote_head:
        print("[push] 无变化")
        return 0
    if remote_head:
        gh.update_ref(owner, repo, branch, new_head, force=force_update)
    else:
        gh._req("POST", "/repos/%s/%s/git/refs" % (owner, repo),
                {"ref": "refs/heads/" + branch, "sha": new_head})
    print("[push] ✓ 远端 %s 已更新为 %s" % (branch, new_head[:8]))

    # 5) 回写本地 remote-tracking ref，让 git status / rev-list 恢复准确。
    #    ⚠️ 某些受限环境里 `git update-ref` 会**报 exit 0 但 ref 不落盘**
    #    （它走 lock 文件 + rename，rename 被拦掉），所以必须写后校验。
    #    校验失败时直接写 ref 文件 —— 此时远端 sha 是用 API 核实过的，
    #    属于「把已确认的远端状态落到本地」，不是在伪造同步状态。
    refname = "refs/remotes/%s/%s" % (args.remote, branch)
    synced = False
    try:
        git("update-ref", refname, new_head)
        synced = git_text("rev-parse", "--verify", refname) == new_head
    except RuntimeError:
        synced = False
    if not synced:
        try:
            gd = git_text("rev-parse", "--git-dir")
            path = os.path.join(gd, *refname.split("/"))
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="ascii") as f:
                f.write(new_head + "\n")
            synced = git_text("rev-parse", "--verify", refname) == new_head
        except Exception as e:                      # noqa: BLE001
            print("[push] ! 回写 remote-tracking ref 失败（不影响远端）: %s" % e)
    if synced:
        print("[push] ✓ 本地 %s 已同步" % refname)
    else:
        print("[push] ! 本地 %s 未能同步；远端已生效，"
              "可联网机器上 `git fetch` 一次即可对齐" % refname)

    return 0


if __name__ == "__main__":
    sys.exit(main())
