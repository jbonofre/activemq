<!--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied.  See the License for the
  specific language governing permissions and limitations
  under the License.
-->

# Contributing to Apache ActiveMQ

Thank you for considering contributing to Apache ActiveMQ. Any contribution (code, test cases, documentation, use cases, ...) is valuable!

This documentation will help you get started. 

## Contribute bug reports and feature requests 

You can report an issue in the ActiveMQ [issue tracker](https://issues.apache.org/jira/projects/AMQ/issues). 

### How to report a bug

Note: If you find a  **security vulnerability**, do _NOT_  open an issue. Please email security@apache.org instead.

When filing an [issue](https://issues.apache.org/jira/projects/AMQ/issues), make sure to answer these five questions:
1. What version of Apache ActiveMQ are you using?
2. What operating system and processor architecture are you using?
3. What did you do?
4. What did you expect to see?
5. What did you see instead?

Troubleshooting questions should be posted on the [public chat](https://the-asf.slack.com/) (no invite needed) or on [dev mailing list](mailto:dev@activemq.apache.org) (you can [subscribe](mailto:dev-subscribe@activemq.apache.org)) instead of the issue tracker. Maintainers and community members will answer your questions there or ask you to file an issue if you’ve encountered a bug. 


### How to suggest a feature or enhancement

If you're looking for a feature that doesn't exist in Apache ActiveMQ, you're probably not alone. Others likely have similar needs. Please open a [Issue](https://issues.apache.org/jira/projects/AMQ/issues) describing the feature you'd like to see, why you need it, and how it should work.

When creating your feature request, document your requirements first. Please, try to not directly describe the solution.

## Before you begin contributing code 

### Review open issues and discuss your approach

If you want to dive into development yourself then you can check out existing open issues or requests for features that need to be implemented. Take ownership of an issue and try fix it. 

Before starting on a large code change, please describe the concept/design of what you plan to do on the issue/feature request you intend to address. If unsure if the design is good or will be accepted, discuss it with the community in the respective issue first, before you do too much active development. 

### Provide your changes in a Pull Request

The best way to provide changes is to fork Apache ActiveMQ repository on GitHub and provide a Pull Request with your changes. To make it easy to apply your changes please use the following conventions:

* Every Pull Request should have a matching GitHub Issue.
* Create a branch that will house your change:

```bash
git clone https://github.com/apache/activemq
cd activemq
git fetch --all
git checkout -b my-branch origin/main
```

  Don't forget to periodically rebase your branch:

```bash
git pull --rebase
git push GitHubUser my-branch --force
```

  Ensure the code is properly formatted:

```bash
./gradlew format
```

* Pull Requests should be based on the `main` branch.
* Test that your changes works by adapting or adding tests. Verify the build passes (see `README.md` for build instructions).
* If your Pull Request has conflicts with the `main` branch, please rebase and fix the conflicts.

## Java version requirements

The Apache ActiveMQ build currently requires Java 17 or later. There are a few tools that help you running the right Java version:


