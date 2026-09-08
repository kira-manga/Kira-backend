package me.manga.kira.backend.complaint.domain

enum class ComplaintPlatform { ANDROID, IOS }

enum class ComplaintKind { REPORT, REPLY, NOTICE }

enum class ComplaintOwnership { INSTALLATION, LEGACY_UNCLAIMED, SYSTEM }

enum class ComplaintType { TECHNICAL, LANGUAGES, SITES_ADD, SITE_ERROR, FEATURES, CUSTOM }

/** UNKNOWN is a recognized legacy value, not a fallback for an unrecognized wire token. */
enum class ComplaintStatus { OPEN, IN_PROGRESS, RESOLVED, CLOSED, PLANNED, PINNED, UNKNOWN, NOT_PLANNED }
