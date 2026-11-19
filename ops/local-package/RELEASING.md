# 发布 Vortex 本地试用版

1. 在 GitHub Actions 打开 **Draft Local Alpha**，选择要交付的分支（通常为默认分支），点击 **Run workflow**。无需预先创建标签。
2. 等待 Windows 和 Linux 的 `Local Memory` 验收通过。在运行摘要点击草稿链接。草稿标题下的标签是 `local-alpha-<运行序号>`，说明中列有实际测试的提交 SHA。
3. 在草稿中核对六个附件。Windows x64 ZIP 自带 Java，通用 ZIP 需要 Java 21，第三个附件是该版本 Temurin 运行时对应的源码。这三项分别配一份同名 `.sha256`。Windows 用户默认下载 `vortex-local-windows-x64.zip`。
4. 在草稿中补充这个版本的变化与已知问题。准备好邀请用户试用时，保持 **Set as a pre-release** 已选并点击 **Publish release**，将此版本的下载页发给用户。

若验收失败，在同次运行的 `vortex-local-evidence-windows-latest` 或 `vortex-local-evidence-ubuntu-latest` 查看日志和验收报告。若上传阶段中断，先检查已有草稿的附件。需要重新生成时，从 **Run workflow** 发起新运行，获得新的预发布序号。
