#!/bin/bash
# Uploads a staged repository to Sonatype.
#
# Usage (must have 'SONATYPE_TOKEN' variable set in its environment):
#   upload-to-sonatype.sh

set -eu

upload_to_sonatype() {
  local staging_url="https://ossrh-staging-api.central.sonatype.com/manual"
  local header="Authorization: Bearer $SONATYPE_TOKEN"

  echo "Starting Sonatype upload process..."
  echo "Using staging URL: $staging_url"
  
  # Query the REST API for the existing Dagger repositories.
  echo "Querying for existing Dagger repositories..."
  local response=$(curl -s --header "$header" -X GET $staging_url/search/repositories?ip=any&profile_id=me.gulya.dagger)

  echo "API Response:"
  echo "$response"

  # Filter to get the list of open repositories.
  echo "Filtering for open repositories..."
  local open_repositories=$(echo $response | jq '.repositories | map(select(.state = "open"))')
  local open_repositories_count=$(echo $open_repositories | jq length)

  echo "Found $open_repositories_count open repositories"

  # Fail if there is not exactly 1 open repository.
  if [[ $open_repositories_count -eq 0 ]]; then
    echo "ERROR: No open repositories found."
    exit 1
  elif [[ $open_repositories_count -gt 1 ]]; then
    echo "ERROR: Expected 1 open repository but found multiple: $(echo $open_repositories | jq)."
    echo "Please drop all unused open repositories and try again."
    exit 1
  fi

  # Now that we know there is exactly one open repository, get its key.
  local repository_key=$(echo $open_repositories | jq -r '.[0].key')
  echo "Using repository key: $repository_key"

  # Finally, upload the staged repository to the Sonatype Publisher Portal.
  echo "Uploading repository to Sonatype Publisher Portal..."
  local upload_response=$(curl -s --header "$header" -X POST $staging_url/upload/repository/$repository_key)
  echo "Upload response: $upload_response"
  echo "Upload completed successfully!"
}

upload_to_sonatype
