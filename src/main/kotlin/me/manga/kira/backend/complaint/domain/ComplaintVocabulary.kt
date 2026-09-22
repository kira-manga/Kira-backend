package me.manga.kira.backend.complaint.domain

enum class ComplaintPlatform { ANDROID, IOS }

enum class ComplaintKind { REPORT, REPLY, NOTICE }

/** LEGACY_UNCLAIMED is recognizable historical vocabulary, never active clean-start ownership. */
enum class ComplaintOwnership { INSTALLATION, LEGACY_UNCLAIMED, SYSTEM }

enum class ComplaintType { TECHNICAL, LANGUAGES, SITES_ADD, SITE_ERROR, FEATURES, CUSTOM }

/** UNKNOWN is recognized but refused by active moderation, never a fallback for an unrecognized wire token. */
enum class ComplaintStatus { OPEN, IN_PROGRESS, RESOLVED, CLOSED, PLANNED, PINNED, UNKNOWN, NOT_PLANNED }
