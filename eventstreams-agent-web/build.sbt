import au.com.eventstreams.{EventStreamsBuild,Dependencies}

EventStreamsBuild.serviceSettings("eventstreams-agent-web")

libraryDependencies ++= Dependencies.eventStreamsHQ

ScoverageSbtPlugin.ScoverageKeys.coverageExcludedPackages := ".*routes"