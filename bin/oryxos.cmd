@echo off
setlocal

rem ============================================================
rem OryxOS launcher (Windows)
rem
rem NOTE: This file is intentionally pure ASCII.
rem A .cmd file is parsed by cmd.exe using the console code page;
rem any non-ASCII byte combined with a wrong code page or line
rem ending can split a line and make cmd run the fragments as
rem commands (symptoms: "'JDK' is not recognized ...").
rem
rem Usage:
rem   oryxos init
rem   oryxos status
rem   oryxos chat --profile default
rem   oryxos serve --port 8080
rem
rem Overridable:
rem   ORYXOS_JAVA_HOME  JDK 21 home (default: JAVA_HOME, then java on PATH)
rem   ORYXOS_JAR        fat JAR path   (default: oryxos-boot/target/oryxos-boot-*.jar)
rem   ORYXOS_JAVA_OPTS  extra JVM options
rem ============================================================

if not defined ORYXOS_JAR (
  for %%f in ("%~dp0..\oryxos-boot\target\oryxos-boot-*.jar") do set "ORYXOS_JAR=%%~ff"
)
if not defined ORYXOS_JAR goto missing_jar

if defined ORYXOS_JAVA_HOME (
  set "JAVA_EXE=%ORYXOS_JAVA_HOME%\bin\java.exe"
) else if defined JAVA_HOME (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_EXE=java"
)

if "%JAVA_EXE%"=="java" goto run
if not exist "%JAVA_EXE%" goto missing_java

:run
"%JAVA_EXE%" %ORYXOS_JAVA_OPTS% -jar "%ORYXOS_JAR%" %*
exit /b %ERRORLEVEL%

:missing_java
echo [oryxos] java.exe not found: "%JAVA_EXE%" 1>&2
echo [oryxos] Install JDK 21 and set JAVA_HOME, or set ORYXOS_JAVA_HOME. 1>&2
exit /b 1

:missing_jar
echo [oryxos] fat JAR not found under "%~dp0..\oryxos-boot\target\" 1>&2
echo [oryxos] Build it first: mvn -DskipTests package 1>&2
echo [oryxos] Or point ORYXOS_JAR at an existing oryxos-boot-*.jar 1>&2
exit /b 1
