docker build -t mirth-backup .
MIRTH_CMD="mirthsync -i -f -s <SERVER_NAME> -u <USERNAME> -p <PASSWORD> -t ./mirth_configs pull"
docker run -v $(pwd)/mirth_configs:/mirth_configs mirth-backup:latest $MIRTH_CMD
