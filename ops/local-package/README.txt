Vortex 个人记忆工作台

Windows 启动
1. 将整个 ZIP 解压到电脑上的新文件夹。
2. 双击 Start Vortex.cmd。Windows x64 版自带 Java。通用版需要已安装的 64 位 Java 21。
3. 等浏览器自动打开工作台。若未打开，请访问启动窗口显示的网址（默认 http://127.0.0.1:8080/）。
4. 使用期间保持启动窗口打开。在窗口按 Ctrl+C 可停止服务。

你的记忆保存在本机
新安装的 Windows 包使用 %LOCALAPPDATA%\Vortex\data。可将此路径粘贴到文件资源管理器地址栏。
启动窗口会显示实际数据目录。普通使用只需本机浏览器，模型已经包含在包内。
每次升级先停止旧服务并备份数据目录，再解压新版本到另外一个文件夹。同一 Windows 用户将自动复用默认数据。
早期 ZIP 的数据在旧目录下的 data\vortex。请先停止旧服务，保留一份备份。若默认数据目录尚不存在，可将旧 data\vortex 整个复制为 %LOCALAPPDATA%\Vortex\data。
已有多个数据目录时，请勿覆盖、合并或同时打开同一目录。可以在新包目录的 PowerShell 中指定旧目录，每次使用同一条命令：
  & '.\Start Vortex.cmd' -DataDir 'D:\旧版Vortex\data\vortex'
也可以通过环境变量 VORTEX_DATA_DIR 持续指定目录。如在原目录启动，启动器会检测并继续使用相邻的旧 data\vortex。
启动器优先采用 -DataDir，其次为 VORTEX_DATA_DIR、当前包相邻的旧数据、当前用户的默认目录。

其他用法
端口占用时，已有 Vortex 的用户可打开原工作台。要换端口可运行以下命令。接入的客户端也须使用此端口。
  & '.\Start Vortex.cmd' -Port 8081
要在服务器启动时保留当前浏览器页面，可以加 -NoBrowser。启动器只监听 127.0.0.1，适合单用户在本机使用。
Linux/macOS（已安装 Java 21）：bash start.sh。默认数据目录为解压目录下的 ./data/vortex，可用 VORTEX_DATA_DIR 指定。

可选的 Agent 客户端
Python SDK：python -m pip install "python/@WHEEL@"
示例：python demo.py
自动提取并检索事实：python agent.py --namespace personal（需配置模型服务凭据，详见 docs/automatic-agent-memory.md）。
Codex MCP：python -m pip install "python/@WHEEL@[mcp]"
启动已安装并登录的 Codex CLI：python ops/run-codex-memory.py --namespace personal
共用记忆的客户端需指向同一个 VORTEX_URL 和 VORTEX_NAMESPACE。请查看 docs/codex-memory.md。

校验
Windows PowerShell 可运行 Get-FileHash -Algorithm SHA256 '下载的压缩包路径'，与同名 .zip.sha256 核对。
manifest.json 列出包内文件摘要。verification.json 记录服务验证范围。此本地试用包未做 Windows 启动脚本签名。
