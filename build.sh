#!/bin/bash

# Pre-requisite:
# Pre-fill lib with jars accordingly to README.md or from http://aigents.com/download/latest/

rm Aigents.jar
mkdir bin
cp -r resources/* bin
cp -r lib/* bin
cd src/main/java
javac -cp ".:./../../../lib/*" -d ./../../../bin -target 1.6 -source 1.6 -Xlint:deprecation $(find ./net/* | grep .java)
cd ./../../../bin
jar cvfm ../Aigents.jar manifest.mf *
cd ..
