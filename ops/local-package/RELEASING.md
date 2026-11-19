# 发布 Vortex 本地试用版

1. 在 GitHub Actions 打开 **Local Memory**，点击 **Run workflow**，选择待交付的分支并勾选 **Create a GitHub prerelease draft after both platforms pass**。标签可填 `local-0.3.0-alpha.1`，留空时使用 `local-alpha-<运行序号>`。
2. 等待同一次运行的 Windows 和 Linux 验收通过。草稿使用这次构建的附件，自动核对提交和 SHA-256，并在运行摘要中给出草稿链接。普通推送、拉取请求和未勾选草稿选项的手动运行都只执行验收。
3. 在草稿中核对六个附件。Windows x64 ZIP 自带 Java，通用 ZIP 需要 Java 21，第三个附件是该版本 Temurin 运行时对应的源码。这三项分别配一份同名 `.sha256`。Windows 用户默认下载 `vortex-local-windows-x64.zip`。
4. 在草稿中补充这个版本的变化与已知问题。准备好邀请用户试用时，保持 **Set as a pre-release** 已选并点击 **Publish release**，将此版本的下载页发给用户。

若验收失败，在同次运行的 `vortex-local-evidence-windows-latest` 或 `vortex-local-evidence-ubuntu-latest` 查看日志和验收报告。若上传中断，先检查该标签的已有草稿及附件。工作流会拒绝覆盖已有草稿或标签。需要发起另一次预发布时，从 **Run workflow** 选择新的标签或留空。
