git switch main
git pull --ff-only origin main
git status
git tag -a v0.1.1 5532890e36b875aa6d4e1f696ce32b3f087f07fb -m "bioskop translation 0.1.1"
git push origin v0.1.1

#git ls-remote --tags origin v0.1.1