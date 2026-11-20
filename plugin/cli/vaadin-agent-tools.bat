@echo off
rem Launcher for vaadin-agent-tools in Windows shells without sh (cmd,
rem PowerShell). Claude Code runs commands and hooks through Git Bash, which
rem uses the sh launcher next to this file; see there for how the CLI runs.
rem
rem Runs VaadinAgentTools.java in Java's source-file mode with the JDK in
rem JAVA_HOME, or else the java on the PATH. Needs Java 17 or later. Exit codes
rem are the CLI's, or 3 when there is no java to run it with.
setlocal

if not defined JAVA_HOME goto usepath
set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if exist "%JAVA_EXE%" goto run
echo vaadin-agent-tools: JAVA_HOME is set to "%JAVA_HOME%", which has no bin\java.exe. Point it at a JDK 17 or later. 1>&2
exit /b 3

:usepath
set "JAVA_EXE=java.exe"
where /q java.exe
if not errorlevel 1 goto run
echo vaadin-agent-tools: needs Java 17 or later, and found no java on the PATH. Install a JDK, or set JAVA_HOME to one. 1>&2
exit /b 3

:run
"%JAVA_EXE%" -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -XX:-UsePerfData "%~dp0VaadinAgentTools.java" %*
exit /b %ERRORLEVEL%
