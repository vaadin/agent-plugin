@echo off
rem Launcher for vaadin-agent-tools in Windows shells without sh (cmd,
rem PowerShell). Claude Code runs commands and hooks through Git Bash, which
rem uses the sh launcher next to this file; see there for how the CLI runs.
rem
rem Runs VaadinAgentTools.java in Java's source-file mode with the JDK in
rem JAVA_HOME, or else the java on the PATH. Needs a JDK 17 or later. Exit codes
rem are the CLI's, or 3 when there is no JDK 17 or later to run it with.
setlocal

if not defined JAVA_HOME goto usepath
set "VAT_JAVA=%JAVA_HOME%\bin\java.exe"
if exist "%VAT_JAVA%" goto check
echo vaadin-agent-tools: JAVA_HOME is set to "%JAVA_HOME%", which has no bin\java.exe. Point it at a JDK 17 or later. 1>&2
exit /b 3

:usepath
set "VAT_JAVA=java.exe"
where /q java.exe
if not errorlevel 1 goto check
echo vaadin-agent-tools: needs a JDK 17 or later, and found no java on the PATH. Install one, or set JAVA_HOME to one. 1>&2
exit /b 3

:check
rem Fail clearly, rather than with compiler errors or a stack trace, unless
rem java can run the CLI: Java 17 or later, with the compiler (the jdk.compiler
rem module), which source-file mode needs and a JRE or a jlink-built runtime may
rem lack. java -version prints, e.g., openjdk version "17.0.2", or java version
rem "1.8.0_402" for Java 8; the patterns below match a quote with "." rather
rem than \", which would upset cmd's own quoting.
set "VAT_PROBE=%TEMP%\vaadin-agent-tools-%RANDOM%%RANDOM%.txt"
set "VAT_VERSION="
set "VAT_TOO_OLD="
set "VAT_COMPILER="
"%VAT_JAVA%" -version >"%VAT_PROBE%" 2>&1
for /f "usebackq tokens=2,3" %%a in ("%VAT_PROBE%") do if "%%a"=="version" if not defined VAT_VERSION set "VAT_VERSION=%%~b"
findstr /r /c:" version .1\." /c:" version .[0-9][^0-9]" /c:" version .1[0-6][^0-9]" "%VAT_PROBE%" >nul && set "VAT_TOO_OLD=1"
"%VAT_JAVA%" --list-modules >"%VAT_PROBE%" 2>nul
findstr /b /c:"jdk.compiler@" "%VAT_PROBE%" >nul && set "VAT_COMPILER=1"
del "%VAT_PROBE%" 2>nul
if not defined VAT_VERSION goto broken
if defined VAT_TOO_OLD goto tooold
if not defined VAT_COMPILER goto nocompiler

"%VAT_JAVA%" -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -XX:-UsePerfData "%~dp0VaadinAgentTools.java" %*
exit /b %ERRORLEVEL%

:broken
echo vaadin-agent-tools: "%VAT_JAVA%" did not run. Check the Java installation. 1>&2
exit /b 3

:tooold
echo vaadin-agent-tools: needs Java 17 or later, but "%VAT_JAVA%" is Java %VAT_VERSION%. Set JAVA_HOME to a JDK 17 or later. 1>&2
exit /b 3

:nocompiler
echo vaadin-agent-tools: "%VAT_JAVA%" is a Java runtime without the compiler (the jdk.compiler module), which running the tool needs. Set JAVA_HOME to a full JDK 17 or later. 1>&2
exit /b 3
