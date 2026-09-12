#!/bin/zsh

cd /Users/k/StudioProjects/ChessTree
./gradlew :composeApp:composeCompatibilityBrowserDistribution
rsync -av --delete composeApp/build/dist/composeWebCompatibility/productionExecutable/ root@151.247.208.76:~/chesstree-release
ssh root@151.247.208.76 'sudo systemctl reload nginx'