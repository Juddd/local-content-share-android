# Use one synchronization and transfer engine

Foreground screens, the system-share foreground service and JobScheduler remain Android lifecycle adapters, but they all delegate Pending Operations and Pending Uploads to one engine. Existing SQLite records and platform scheduling are retained for upgrade compatibility; WorkManager was rejected because it would add a second scheduler without improving the domain interface.
