#!/bin/bash
cd `dirname $0`
java -Duser.language=en -Duser.country=CA -jar target/imap-alarms-standalone-1.0-SNAPSHOT-jar-with-dependencies.jar "$@"
