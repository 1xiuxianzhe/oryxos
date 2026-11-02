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
rem Java lookup order: ORYXOS_JAVA_HOME -> JAVA_HOME -> java on PATH.
rem Requires JDK 21+.
rem
rem Overridable:
rem   ORYXOS_JAR        fat JAR path   (default: newest oryxos-boot/target/oryxos-boot-*.jar)
rem   ORYXOS_JAVA_OPTS  extra JVM options
rem ============================================================

rem Pick the most recently built fat JAR (skip -sources / -javadoc if ever produced).
if not defined ORYXOS_JAR (
  for /f "delims=" %%f in ('dir /b /o-d /a-d "%~dp0..\oryxos-boot\target\oryxos-boot-*.jar" 2^>nul ^| findstr /v /i "\-sources.jar \-javadoc.jar"') do (
    set "ORYXOS_JAR=%~dp0..\oryxos-boot\target\%%f"
    goto :jar_found
  )
)
:jar_found
if not defined ORYXOS_JAR goto missing_jar

if defined ORYXOS_JAVA_HOME (
  set "JAVA_EXE=%ORYXOS_JAVA_HOME%\bin\java.exe"
) else if defined JAVA_HOME (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_EXE=java"
)

if "%JAVA_EXE%"=="java" goto check_version
if not exist "%JAVA_EXE%" goto missing_java

:check_version
rem Write the version banner to a temp file: passing a quoted path with spaces
rem directly into `for /f ('...')` loses the quotes and breaks on "C:\Program Files\...".
set "JAVA_VER="
set "ORYXOS_VER_FILE=%TEMP%\oryxos-java-version.txt"
"%JAVA_EXE%" -version 2> "%ORYXOS_VER_FILE%"
for /f "tokens=3" %%v in ('type "%ORYXOS_VER_FILE%" ^| findstr /i "version"') do set "JAVA_VER=%%~v"
del "%ORYXOS_VER_FILE%" >nul 2>&1
if not defined JAVA_VER goto run
for /f "tokens=1,2 delims=._-" %%a in ("%JAVA_VER%") do (
  if "%%a"=="1" (set "JAVA_MAJOR=%%b") else (set "JAVA_MAJOR=%%a")
)
if not defined JAVA_MAJOR goto run
if %JAVA_MAJOR% LSS 21 goto old_java
goto run

:run
"%JAVA_EXE%" %ORYXOS_JAVA_OPTS% -jar "%ORYXOS_JAR%" %*
exit /b %ERRORLEVEL%

:old_java
echo [oryxos] Java %JAVA_VER% found, but OryxOS requires Java 21 or newer. 1>&2
echo [oryxos] Point JAVA_HOME or ORYXOS_JAVA_HOME at a JDK 21 install. 1>&2
exit /b 1

:missing_java
echo [oryxos] java.exe not found: "%JAVA_EXE%" 1>&2
echo [oryxos] Install JDK 21 and set JAVA_HOME, or set ORYXOS_JAVA_HOME. 1>&2
exit /b 1

:missing_jar
echo [oryxos] fat JAR not found under "%~dp0..\oryxos-boot\target\" 1>&2
echo [oryxos] Build it first: mvn -DskipTests package 1>&2
echo [oryxos] Or point ORYXOS_JAR at an existing oryxos-boot-*.jar 1>&2
exit /b 1
