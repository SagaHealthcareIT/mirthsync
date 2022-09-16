@ECHO OFF
SET mypath=%~dp0
SET mypath=%mypath:~0,-1%

java -jar %mypath%\..\lib\mirthsync-3.0.4-SNAPSHOT-standalone.jar %*
