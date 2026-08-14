@echo off
rem ---------------------------------------------------------------------------
rem Запуск wdl-скрипта через модуль Gradle :wdl-cli.
rem
rem   run-wdl.bat <путь к скрипту> [рабочий каталог] [кодировка вывода]
rem
rem Первый аргумент - путь к .wdl-файлу, второй - каталог, из которого его
rem выполнять: относительные пути (и в аргументе, и внутри скрипта) считаются
rem от него. Без второго аргумента берётся корень репозитория - как у `gradlew run`.
rem
rem Третий аргумент (или переменная WDL_ENCODING) задаёт кодировку вывода скрипта
rem там, где консоли нет и определить её нечем - например, при запуске из IDE:
rem   run-wdl.bat hello.wdl D:\code\examples UTF-8
rem
rem Файл хранится в кодировке cp866: cmd.exe читает батник в кодовой странице
rem консоли, и в UTF-8 русские комментарии ломают разбор команд.
rem ---------------------------------------------------------------------------
setlocal

rem Завершающий слэш из путей убираем: `"D:\dir\"` доезжает до Java с кавычкой на конце.
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"

if "%~1"=="" goto :usage

set "SCRIPT=%~1"
if "%~2"=="" (set "WORKDIR=%ROOT%") else (set "WORKDIR=%~2")
if "%WORKDIR:~-1%"=="\" set "WORKDIR=%WORKDIR:~0,-1%"

if not exist "%WORKDIR%\" (
    echo Рабочий каталог не найден: %WORKDIR%>&2
    exit /b 2
)

rem Существование скрипта проверяем в целевом каталоге: путь к нему пользователь
rem пишет так же, как его увидит сам wdl-cli.
pushd "%WORKDIR%"
if not exist "%SCRIPT%" (
    echo Файл скрипта не найден: %SCRIPT% ^(искали в %CD%^)>&2
    popd
    exit /b 2
)
popd

set "ENC=%~3"
if not defined ENC set "ENC=%WDL_ENCODING%"
if not defined ENC call :detect_encoding

call "%ROOT%\gradlew.bat" -p "%ROOT%" :wdl-cli:run -q --console=plain ^
    -Pwdl.dir="%WORKDIR%" -Pwdl.encoding=%ENC% "--args=%SCRIPT%"
exit /b %ERRORLEVEL%

rem Кодовая страница консоли, в именах Java: 866 -> cp866, 65001 -> UTF-8.
:detect_encoding
for /f "tokens=2 delims=:" %%c in ('chcp') do set "CP=%%c"
set "CP=%CP: =%"
if "%CP%"=="65001" (set "ENC=UTF-8") else (set "ENC=cp%CP%")
exit /b 0

:usage
echo Использование: %~nx0 ^<путь к скрипту^> [рабочий каталог] [кодировка вывода]>&2
echo   %~nx0 examples\hello.wdl>&2
echo   %~nx0 hello.wdl "%ROOT%\examples">&2
echo   %~nx0 hello.wdl "%ROOT%\examples" UTF-8>&2
exit /b 1
