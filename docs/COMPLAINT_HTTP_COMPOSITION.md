# Complaint HTTP composition

The current application supports only **disabled** complaints. The registered
`DisabledComplaintRoutesFilter` runs after request diagnostics but before request
body buffering and Spring Security. Installation, owner and Admin complaint
namespaces and the operation-status route return a constant bounded 404 with
contract version 1 and `Cache-Control: no-store, no-transform`, without reading
credentials or reaching the user-token/database path. Unrelated routes are unchanged.

There is no switch that enables the unfinished complaint implementation. This
filter is not an enabled deployment's maintenance or restore-quarantine policy:
those states must retain the specified deletion/recovery continuation routes.
The reviewed installation security chain, genuine configuration/activation and
recovery authority, test-scope transactions and application/controller composition
must replace this closed composition together before either enabled mode can be
supported. Existing parsers/stores/key codecs are not an activation mechanism.

This implements the disabled HTTP boundary only. No complaint cutover, issue
completion, Firebase retirement, legacy import, deployment or Store upload is implied.
