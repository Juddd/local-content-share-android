# Content Transfer Android Client

The Android client keeps a local working collection responsive while reliably converging it with one configured Local Content Share server.

## Language

**Local Item**:
The latest locally known form of a Content Item, including its synchronization state and any conflict.
_Avoid_: Cached card, server row

**Pending Operation**:
A durable user-authored Content Mutation waiting for server confirmation.
_Avoid_: Request, retry record

**Pending Upload**:
A durable staged file waiting to become a committed Content Item in Files.
_Avoid_: Share task, temporary copy

**Synchronization Conflict**:
A server revision that diverged from the base revision of a Pending Operation and requires an explicit resolution.
_Avoid_: Upload failure, network error

**Transfer Progress**:
The shared state of a Pending Upload as observed by the foreground screen, system-share notification, or background retry runner.
_Avoid_: Dialog progress, notification progress
