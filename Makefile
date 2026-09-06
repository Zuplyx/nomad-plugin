# Convenience targets for this fork.
#
# Note the upstream release flow does NOT work here. The pom's <scm> deliberately still
# points at jenkinsci/nomad-plugin so the topic branches stay contributable, so
# maven-release-plugin would try to push there over SSH, and release:perform would try to
# deploy to repo.jenkins-ci.org. This fork publishes to neither; `make release` below just
# builds a versioned .hpi you install by hand.
#
# Requires JDK 17 or newer. Maven picks up repo.jenkins-ci.org from ~/.m2/settings.xml
# (see https://www.jenkins.io/doc/developer/tutorial/prepare/); ci/settings.xml is for CI
# only and is not used here, because -s would override your own Maven settings.

MVN = mvn -B -ntp

# Appended to <revision> from pom.xml to form the release version, e.g. 0.11.0-fork.
# Everyday builds keep the pom's default -SNAPSHOT.
RELEASE_SUFFIX = -fork

.PHONY: help build test hpi release spotbugs clean

help:
	@echo "build    - compile, test and package (version from pom.xml, -SNAPSHOT)"
	@echo "test     - run the tests only"
	@echo "hpi      - package without running tests"
	@echo "release  - build target/nomad.hpi as $(RELEASE_SUFFIX) release, then print the tag command"
	@echo "spotbugs - run SpotBugs"
	@echo "clean    - remove target/"

build:
	@$(MVN) clean verify

test:
	@$(MVN) test

hpi:
	@$(MVN) clean package -DskipTests

# Overrides ${changelist} on the command line, so no pom edit and no -SNAPSHOT state is
# left behind. Tagging and pushing stay manual and deliberate.
release:
	@$(MVN) -Dchangelist=$(RELEASE_SUFFIX) clean verify
	@v=$$($(MVN) -q -Dchangelist=$(RELEASE_SUFFIX) \
		help:evaluate -Dexpression=project.version -DforceStdout); \
	echo; \
	echo "Built target/nomad.hpi as version $$v"; \
	echo "Install via Manage Jenkins > Plugins > Advanced settings > Deploy Plugin."; \
	echo; \
	echo "To tag and publish (the Release workflow builds and attaches the .hpi):"; \
	echo "    git tag -a v$$v -m 'Release v$$v'"; \
	echo "    git push fork v$$v"

spotbugs:
	@$(MVN) spotbugs:check

clean:
	@$(MVN) clean
