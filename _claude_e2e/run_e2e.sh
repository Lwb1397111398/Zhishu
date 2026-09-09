#!/bin/bash
# 一键重启 mock 服务器并跑 E2E（防止 Windows 上 8199 端口多进程叠绑）
set -e
ROOT="/c/AIWorkSpace/知识学习小说工具"
cd "$ROOT"

# 杀掉所有旧的 mock_llm 进程
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { \$_.CommandLine -like '*mock_llm*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" >/dev/null 2>&1 || true
sleep 1

# 只启动一个新的
cd "$ROOT/_claude_e2e"
nohup python mock_llm.py 8199 >/dev/null 2>&1 &
echo $! > /tmp/mockpid
sleep 2
echo "==== RUN MARKER $(date +%H:%M:%S) ====" >> "$ROOT/_claude_e2e/server.log"

# 校验只有一个监听
N=$(netstat -ano 2>/dev/null | grep -c ":8199.*LISTENING" || true)
echo "listeners on 8199: $N"

export JAVA_HOME="C:\\Users\\jingyue\\AndroidTools\\jdk\\jdk-17.0.20.1+1"
export ZHISHU_RUN_SAMPLES="true"
export ZHISHU_SAMPLE_BASE_URL="http://127.0.0.1:8199/v1"
export ZHISHU_SAMPLE_MODEL="mock-llm"
export ZHISHU_SAMPLE_API_KEY="mock-key"
export ZHISHU_E2E_TOPIC="${ZHISHU_E2E_TOPIC:-天空为什么是蓝色的}"
export ZHISHU_E2E_CHAPTERS="${ZHISHU_E2E_CHAPTERS:-2}"

cd "$ROOT"
./gradlew.bat :app:runE2eFullFlow --max-workers=1 \
  -Dorg.gradle.java.home="C:\\Users\\jingyue\\AndroidTools\\jdk\\jdk-17.0.20.1+1" \
  --console=plain 2>&1 | tail -8
