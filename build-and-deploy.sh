#!/bin/zsh

./gradlew :composeApp:composeCompatibilityBrowserDistribution
rsync -av --delete /Users/k/StudioProjects/ChessTree/composeApp/build/dist/composeWebCompatibility/productionExecutable/ root@151.247.208.76:~/chesstree-release
ssh root@151.247.208.76 'sudo rsync -a --delete ~/chesstree-release/ /var/www/chesstree/'
ssh root@151.247.208.76 'sudo nginx -t'
ssh root@151.247.208.76 'sudo systemctl reload nginx'