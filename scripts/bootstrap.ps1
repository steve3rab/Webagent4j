$ErrorActionPreference = "Stop"

git config core.hooksPath .githooks
& .\mvnw.cmd --version
Write-Host "WebAgent4J development hooks are enabled."
