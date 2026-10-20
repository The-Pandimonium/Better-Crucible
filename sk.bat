@echo off
rem The one command of this project: sk COMMAND [ARGS]    (sk help lists them; docs\building.md says what each does)
rem   sk setup           first: the libraries, the game's jars, the deobfuscated jar
rem   sk all             builds the server (work\dist\sk-emu.jar) and every client mod (work\dist\sk-ID.zip)
rem   sk test client     the real-client test       sk run   starts the server       sk mappings update   after the game was updated
rem Needs a JDK 25 (JAVA_HOME, or javac on the PATH, or the JDK in the game's java_vm), and the Spiral Knights folder (SK_GAME_DIR, or local.properties, or the Steam folder).
setlocal
cd /d "%~dp0"
if "%SK_GAME_DIR%"=="" set "SK_GAME_DIR=C:\Program Files (x86)\Steam\steamapps\common\Spiral Knights"
set "JAVA="
if not "%JAVA_HOME%"=="" if exist "%JAVA_HOME%\bin\javac.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if "%JAVA%"=="" if exist "%SK_GAME_DIR%\java_vm\bin\javac.exe" set "JAVA=%SK_GAME_DIR%\java_vm\bin\java.exe"
if "%JAVA%"=="" (where javac >nul 2>nul && set "JAVA=java")
if "%JAVA%"=="" (
  echo No JDK found: there is no javac in JAVA_HOME, in the game's java_vm or on PATH.
  echo Install JDK 25 or newer ^(for example Temurin 25^), or set JAVA_HOME to it.
  exit /b 1
)
if "%ENIGMA_JAR%"=="" for %%f in (lib\enigma-cli-*.jar) do set "ENIGMA_JAR=%%f"
if not exist "%ENIGMA_JAR%" (
  echo The Enigma CLI is not in lib\ ^(lib\enigma-cli-*.jar^): put the project's build of it there, or set ENIGMA_JAR.
  exit /b 1
)
"%JAVA%" -cp "%ENIGMA_JAR%" tools\build\Build.java %*
exit /b %ERRORLEVEL%
