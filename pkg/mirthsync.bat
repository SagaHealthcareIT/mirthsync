@ECHO OFF
SET mypath=%~dp0
SET mypath=%mypath:~0,-1%

java -jar %mypath%\lib\uberjar\mirthsync-2.0.2-SNAPSHOT-standalone.jar %*
