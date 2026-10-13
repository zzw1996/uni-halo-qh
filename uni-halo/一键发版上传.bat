@echo off
rem ============================================================
rem  一键发版上传：出包(发行产物) -> 体积体检 -> 命令行上传
rem  为什么要它：开发者工具里点"上传"用的是【当前窗口】的项目，
rem  而"运行到小程序模拟器"开的窗口指向 dist\dev（未压缩，主包 2.3MB，永远超限）。
rem  本脚本用命令行直接指定 dist\build 上传，绕开窗口选择。
rem  用法：双击本文件；或  一键发版上传.bat -n   只出包+体检，不上传
rem
rem  两个必须遵守的实现约束（都踩过）：
rem  1) 本文件必须存成 GBK + CRLF：存 UTF-8 时 cmd 会按当前代码页解析出错，
rem     表现为 "'ho' is not recognized as an internal or external command"。
rem  2) 本文件不能用 setlocal enabledelayedexpansion：微信开发者工具的 cli.bat 里
rem     有一段含 ! 的 JS bootstrap，延迟展开会把 ! 吃掉，报 SyntaxError。
rem     另外 cli.bat 自己会 chcp 65001，回来要 chcp 936 复原，否则后面的中文变乱码。
rem  3) if ( ... ) 块里的 echo 不能出现半角括号：cmd 会把 ")" 当成块结束符，
rem     实测报「此时不应有 ...」。要列条目就用 "- " 开头。
rem ============================================================
setlocal
cd /d "%~dp0"

set "DRY="
if /i "%~1"=="-n" set "DRY=1"

echo ============================================
echo   uni-halo 小程序  一键发版上传
echo ============================================
echo.

where node >nul 2>nul
if errorlevel 1 (
  echo [错误] 没找到 node，请先安装 Node.js 并加入 PATH。
  goto :fail
)

echo [1/4] 出包并体检（约 25 秒）...
rem 出包脚本的输出是 UTF-8，把控制台临时切到 65001 才不乱码；下一行立刻切回来
chcp 65001 >nul
node tools\build-miniapp-release.cjs
set "RC=%errorlevel%"
chcp 936 >nul
if not "%RC%"=="0" (
  echo.
  echo [错误] 出包或体积体检没过，已中止。原因见上面的输出。
  goto :fail
)

rem ---- 版本号：默认取 manifest.json 的 versionName ----
set "VER="
for /f "delims=" %%v in ('node tools\miniapp-version.cjs') do set "VER=%%v"
set "INPUT="
set /p "INPUT=版本号（直接回车用 manifest.json 里的 %VER%）: "
if not "%INPUT%"=="" set "VER=%INPUT%"
if "%VER%"=="" (
  echo [错误] 没拿到版本号。
  goto :fail
)

rem ---- 找微信开发者工具的 cli.bat ----
set "CLI="
for %%d in (C D E F) do (
  if not defined CLI if exist "%%d:\chengxu\微信web开发者工具\cli.bat" set "CLI=%%d:\chengxu\微信web开发者工具\cli.bat"
  if not defined CLI if exist "%%d:\Program Files (x86)\Tencent\微信web开发者工具\cli.bat" set "CLI=%%d:\Program Files (x86)\Tencent\微信web开发者工具\cli.bat"
  if not defined CLI if exist "%%d:\Program Files\Tencent\微信web开发者工具\cli.bat" set "CLI=%%d:\Program Files\Tencent\微信web开发者工具\cli.bat"
  if not defined CLI if exist "%%d:\微信web开发者工具\cli.bat" set "CLI=%%d:\微信web开发者工具\cli.bat"
)
if not defined CLI (
  echo [错误] 没找到微信开发者工具的 cli.bat，请把它的路径补进本文件的查找列表。
  goto :fail
)

if defined DRY (
  echo.
  echo [2/4] dry-run：跳过上传。实际会执行下面这条：
  echo   "%CLI%" upload --project "%~dp0unpackage\dist\build\mp-weixin" -v %VER% -d "发版 %DATE% %TIME:~0,5%"
  echo.
  pause
  exit /b 0
)

echo.
echo [2/4] 检查登录状态...
call "%CLI%" islogin
set "RC=%errorlevel%"
chcp 936 >nul
if not "%RC%"=="0" (
  echo.
  echo [错误] 连不上开发者工具：请先打开微信开发者工具并登录，
  echo        并在 设置 - 安全设置 里开启服务端口。
  goto :fail
)

echo.
echo [3/4] 上传（版本 %VER%，源目录 unpackage\dist\build\mp-weixin）...
call "%CLI%" upload --project "%~dp0unpackage\dist\build\mp-weixin" -v %VER% -d "发版 %DATE% %TIME:~0,5%"
set "RC=%errorlevel%"
chcp 936 >nul
if not "%RC%"=="0" (
  echo.
  echo [失败] 上传没通过，看上面的报错。常见原因：
  echo        - 版本号已被占用：换一个版本号，同时改 uni-halo\manifest.json 的 versionName
  echo        - 开发者工具没登录，或服务端口没开
  goto :fail
)

echo.
echo [4/4] 完成。去微信公众平台 - 版本管理：设为体验版或提交审核。
echo.
pause
exit /b 0

:fail
echo.
pause
exit /b 1
