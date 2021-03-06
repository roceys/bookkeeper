---
title: BookKeeper Testing Guide
---

* TOC
{:toc}

## Overview

Apache BookKeeper is a well adopted software project with a strong commitment to testing.
Consequently, it has many testing-related needs. It requires precommit tests to ensure
code going to the repository meets a certain quality bar and it requires ongoing postcommit
tests to make sure that more subtle changes which escape precommit are nonetheless caught.
This document outlines how to write tests, which tests are appropriate where, and when tests
are run, with some additional information about the testing systems at the bottom.

## Testing Scenarios

With the tools at our disposal, we have a good set of utilities which we can use to verify
BookKeeper correctness. To ensure an ongoing high quality of code, we use precommit and postcommit
testing.

### Precommit

For precommit testing, BookKeeper uses [Jenkins](https://builds.apache.org/job/bookkeeper-seed/) and
[Travis CI](https://travis-ci.org/apache/bookkeeper), hooked up to
[Github](https://github.com/apache/bookkeeper), to ensure that pull requests meet a certain quality bar.
These precommits verify correctness via unit/integration tests.

Precommit tests are kicked off when a user makes a Pull Request against the `apache/bookkeeper` repository,
and the Jenkins and Travis CI statuses are displayed at the bottom of the pull request page. Clicking on
"Details" will open the status page in the selected tool; there, test status and output can be viewed.

For retriggering precommit testing for a given Pull Request, you can comment "retest this please" for
jenkins jobs and close/reopen the Pull Request for travis jobs.

### Postcommit

Running in postcommit removes as stringent of a time constraint, which gives us the ability to do some
more comprehensive testing. Currently in postcommit, we run unit/integration tests against both master and
the most recent release branch, publish website for any changes related to website and documentation, and
deploy a snapshot of latest master to artifactory.

Postcommit test results can be found in [Jenkins](https://builds.apache.org/job/bookkeeper-seed/).

### Configuration

All the precommit and postcommit CI jobs are managed either by Jenkins or Travis CI.

For Jenkins jobs, they are all written and managed using [Jenkin-DSL](https://github.com/jenkinsci/job-dsl-plugin/wiki).
The DSL scripts are maintained under [.test-infra](https://github.com/apache/bookkeeper/tree/master/.test-infra/jenkins).
Any jenkins changes should be made in these files and reviewed by the community.

For Travis CI jobs, they are defined in [.travis.yml](https://github.com/apache/bookkeeper/blob/master/.travis.yml).
Any travis CI changes should be made in this file and reviewed by the community.
