# Delete the kind cluster and everything in it, the Postgres volume included.
$cluster = if ($env:CLUSTER) { $env:CLUSTER } else { 'tally' }
& kind delete cluster --name $cluster
exit $LASTEXITCODE
