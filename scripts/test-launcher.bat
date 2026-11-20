@echo off
rem Checks the cmd launcher, plugin\cli\vaadin-agent-tools.bat, the way the
rem skills run it on Windows without sh: the CLI's exit codes, and exit code 3
rem with a message for a missing JDK, a runtime without the compiler, and, when
rem OLD_JDK names one, a JDK older than 17. scripts/test-cli.sh covers the rest.
rem Run it from the repository root with a JDK 17 or later in JAVA_HOME:
rem
rem   scripts\test-launcher.bat
setlocal
set "CLI=plugin\cli\vaadin-agent-tools.bat"
set "FAILURES=0"
set "WORK=%TEMP%\vaadin-agent-tools-test-%RANDOM%%RANDOM%"
set "REAL_JAVA_HOME=%JAVA_HOME%"
mkdir "%WORK%"

call :expect 0 "a clean project passes" "check-theme-mixing test\fixtures\clean --json" ""
call :expect 1 "a mixed project fails" "check-theme-mixing test\fixtures\mixed" ""
call :expect 2 "a missing project is a usage error" "check-aura-usage test\fixtures\does-not-exist" ""

set "JAVA_HOME=%WORK%\no-jdk"
call :expect 3 "a missing JDK is reported" "check-theme-mixing ." "JAVA_HOME"

"%REAL_JAVA_HOME%\bin\jlink.exe" --add-modules java.base --output "%WORK%\jre"
set "JAVA_HOME=%WORK%\jre"
call :expect 3 "a runtime without the compiler is reported" "check-theme-mixing ." "jdk.compiler"

if not defined OLD_JDK goto done
set "JAVA_HOME=%OLD_JDK%"
call :expect 3 "a JDK older than 17 is reported" "check-theme-mixing ." "needs Java 17 or later"

:done
set "JAVA_HOME=%REAL_JAVA_HOME%"
rmdir /s /q "%WORK%"
if not "%FAILURES%"=="0" goto failed
echo launcher checks passed
exit /b 0

:failed
echo %FAILURES% launcher checks failed
exit /b 1

rem :expect <exit code> <description> <arguments> <text the error output must have>
:expect
call %CLI% %~3 >"%WORK%\stdout.txt" 2>"%WORK%\stderr.txt"
set "GOT=%ERRORLEVEL%"
if not "%GOT%"=="%~1" goto expect_wrong_code
if not "%~4"=="" findstr /c:"%~4" "%WORK%\stderr.txt" >nul || goto expect_no_message
findstr /c:"Exception" "%WORK%\stderr.txt" >nul && goto expect_stack_trace
echo ok    %~2
exit /b 0

:expect_wrong_code
echo FAIL  %~2: exit code %GOT%, want %~1
type "%WORK%\stderr.txt"
set /a FAILURES+=1
exit /b 0

:expect_no_message
echo FAIL  %~2: the error output lacks "%~4"
type "%WORK%\stderr.txt"
set /a FAILURES+=1
exit /b 0

:expect_stack_trace
echo FAIL  %~2: printed a stack trace
type "%WORK%\stderr.txt"
set /a FAILURES+=1
exit /b 0
