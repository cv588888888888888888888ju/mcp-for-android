# 版本规则 (VERSION-RULE)

本仓库受一条版本规则约束：**文件 `VERSION` 的内容必须与 git 最新 tag 一致**（tag 前缀 `v` 可省略）。

## 含义
- `VERSION` 文件保存当前语义版本，例如 `0.1.0`。
- git tag 以 `v` + 版本号命名，例如 `v0.1.0`。
- `pre-commit` 钩子会在每次提交前校验二者一致，不一致则拒绝提交。

## 发版流程
1. 修改代码。
2. 更新 `VERSION` 为新高版本（如 `0.2.0`）。
3. `git add VERSION`
4. `git commit -m "release: v0.2.0"`
5. `git tag v0.2.0`
6. `git push --tags`

> 提示：若要在同一次提交里同时改 VERSION 并打 tag，请先 `git tag vX` 再 `git commit`，
> 这样 pre-commit 校验时最新 tag 已是 vX，与 VERSION 一致即可通过。
