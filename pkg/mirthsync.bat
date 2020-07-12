@ECHO OFF
SET mypath=%~dp0
SET mypath=%mypath:~0,-1%

java -jar %mypath%\..\lib\mirthsync-2.0.6-standalone.jar %*
