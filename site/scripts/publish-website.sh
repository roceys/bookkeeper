#!/bin/bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

# NOTE: this is the script used by CI to push to apache. If you are looking for
#       staging the changes, try the `staging-website.sh` script.

source scripts/common.sh

ORIGIN_REPO=$(git remote show origin | grep 'Push  URL' | awk -F// '{print $NF}')
echo "ORIGIN_REPO: $ORIGIN_REPO"

(
  cd $ROOT_DIR

  git checkout asf-site
  git reset origin/asf-site

  touch .
  rm -r content
  mv $APACHE_GENERATED_DIR/content content
  git add -A content

  git commit -m "Updated site at revision $REVISION"
  # git push origin asf-site
)
