"""Bounded policy for rendered Kubernetes tutorial-media configuration, not a cluster test.

Stdlib unittest discovery runs PolicyUnitTests without importing PyYAML. The explicit
test-rendered command runs the pinned-parser tests and mutations of the actual rendered
base. check-template cannot be used as check-resolved: their modes are fixed commands.
No secrets, Kubernetes API, image inspection, storage access or Spring interpreter here.
"""

import argparse
import contextlib
import copy
import io
from pathlib import Path
import re
import sys
import unittest


TEMPLATE_ONLY = "TEMPLATE_ONLY"
RESOLVED_CONFIG = "RESOLVED_CONFIG"
MEDIA_PATH = "/var/lib/kira/tutorial-media"
CLAIM_NAME = "kira-tutorial-media"
TEMPLATE_CLASS = "replace-with-installed-rwx-class"
TEMPLATE_CAPACITY = "1"
CLASS_STATE = "kira.manga/storage-class-state"
CAPACITY_STATE = "kira.manga/storage-capacity-state"
MEDIA_ENV = "KIRA_TUTORIAL_MEDIA_DIRECTORY"
SEED_ENV = "KIRA_TUTORIAL_SEED_ENABLED"
SECRET_ENV = {
    "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD",
    "SPRING_DATA_REDIS_URL", "KIRA_JWT_SECRET", "KIRA_SIGNING_ACTIVE_KEY_ID",
    "KIRA_SIGNING_PRIVATE_KEY", "KIRA_SIGNING_VERIFICATION_KEYS_0_KEY_ID",
    "KIRA_SIGNING_VERIFICATION_KEYS_0_PUBLIC_KEY",
}
SPRING_ENV = {name for name in SECRET_ENV if name.startswith("SPRING_")} | {"SPRING_PROFILES_ACTIVE"}
UNINSPECTED_SECRET = object()
POD_SECURITY = {
    "runAsNonRoot": True, "runAsUser": 10001, "runAsGroup": 10001, "fsGroup": 10001,
    "seccompProfile": {"type": "RuntimeDefault"},
}
PROBES = {
    "startupProbe": {
        "httpGet": {"path": "/actuator/health/liveness", "port": "management"},
        "failureThreshold": 30, "periodSeconds": 5, "timeoutSeconds": 2,
    },
    "livenessProbe": {
        "httpGet": {"path": "/actuator/health/liveness", "port": "management"},
        "periodSeconds": 15, "timeoutSeconds": 3, "failureThreshold": 3,
    },
    "readinessProbe": {
        "httpGet": {"path": "/actuator/health/readiness", "port": "management"},
        "periodSeconds": 10, "timeoutSeconds": 3, "failureThreshold": 3,
    },
}


class Refused(ValueError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def require(condition, code, message):
    if not condition:
        raise Refused(code, message)


def same(actual, expected):
    """Equality without Python's bool-as-int or nested scalar coercion."""
    if type(actual) is not type(expected):
        return False
    if type(expected) is dict:
        return actual.keys() == expected.keys() and all(same(actual[k], v) for k, v in expected.items())
    if type(expected) is list:
        return len(actual) == len(expected) and all(same(a, b) for a, b in zip(actual, expected))
    return actual == expected


def exact(actual, expected, code, message):
    require(same(actual, expected), code, message)


def mapping(value, code):
    require(type(value) is dict and all(type(k) is str for k in value), code, "expected string-key mapping")
    return value


def sequence(value, code):
    require(type(value) is list, code, "expected list")
    return value


def named_entries(items, label):
    result = {}
    for item in sequence(items, label + "_SHAPE"):
        item = mapping(item, label + "_SHAPE")
        name = item.get("name")
        require(type(name) is str and bool(name), label + "_SHAPE", "explicit name required")
        require(name not in result, label + "_DUPLICATE", "duplicate named entry")
        result[name] = item
    return result


def load_rendered(raw):
    # Deliberately lazy: the verify job discovers this file using stdlib-only Python.
    try:
        import yaml
    except ImportError as exc:
        raise Refused("YAML_DEPENDENCY", "use the pinned run-owned parser venv") from exc
    exact(yaml.__version__, "6.0.3", "YAML_DEPENDENCY", "PyYAML 6.0.3 is required")

    class StrictSafeLoader(yaml.SafeLoader):
        def compose_node(self, parent, index):
            require(not self.check_event(yaml.AliasEvent), "YAML_ALIAS", "YAML aliases are unsupported")
            return super().compose_node(parent, index)

        def construct_mapping(self, node, deep=False):
            require(isinstance(node, yaml.MappingNode), "YAML_MAPPING", "expected YAML mapping")
            result = {}
            for key_node, value_node in node.value:
                require(key_node.tag != "tag:yaml.org,2002:merge", "YAML_MERGE", "YAML merges are unsupported")
                key = self.construct_object(key_node, deep=deep)
                require(type(key) is str, "YAML_KEY_TYPE", "only string mapping keys are supported")
                require(key != "<<", "YAML_MERGE", "YAML merge keys are unsupported")
                require(key not in result, "YAML_DUPLICATE_KEY", "duplicate YAML mapping key")
                result[key] = self.construct_object(value_node, deep=deep)
            return result

    try:
        return list(yaml.load_all(raw, Loader=StrictSafeLoader))
    except yaml.YAMLError as exc:
        # Do not echo rendered values (which an unsupported overlay could make sensitive).
        raise Refused("YAML_INVALID", "invalid YAML or unsupported YAML tag") from exc


def resource_index(documents):
    require(type(documents) is list and bool(documents), "RESOURCE_SHAPE", "rendered documents required")
    index = {}
    for document in documents:
        document = mapping(document, "RESOURCE_SHAPE")
        metadata = mapping(document.get("metadata"), "RESOURCE_SHAPE")
        kind, name = document.get("kind"), metadata.get("name")
        namespace = metadata.get("namespace", "")
        require(all(type(v) is str and bool(v) for v in (document.get("apiVersion"), kind, name)),
                "RESOURCE_IDENTITY", "explicit apiVersion, kind and name required")
        require(type(namespace) is str, "RESOURCE_IDENTITY", "namespace must be a string")
        if kind in {"Deployment", "ConfigMap", "PersistentVolumeClaim", "PodDisruptionBudget"}:
            require(bool(namespace), "RESOURCE_IDENTITY", "relevant rendered resources need explicit namespaces")
        key = (kind, namespace, name)
        require(key not in index, "RESOURCE_DUPLICATE", "duplicate kind/namespace/name")
        index[key] = document
    return index


def resource(index, kind, namespace, name):
    key = (kind, namespace, name)
    require(key in index, "RESOURCE_REQUIRED", "required same-namespace resource is absent")
    expected_api = {"ConfigMap": "v1", "PersistentVolumeClaim": "v1", "PodDisruptionBudget": "policy/v1"}
    exact(index[key]["apiVersion"], expected_api[kind], "RESOURCE_IDENTITY", "unexpected resource API")
    return index[key]


def env_name(name):
    require(type(name) is str, "ENV_NAME", "environment name must be a string")
    compact = re.sub(r"[^A-Za-z0-9]", "", name).upper()
    allowed = not compact.startswith("KIRATUTORIAL") or name in {MEDIA_ENV, SEED_ENV}
    allowed = allowed and (not compact.startswith("SPRING") or name in SPRING_ENV)
    allowed = allowed and not compact.startswith(("JAVA", "JDK", "JRE", "LOADER"))
    allowed = allowed and compact not in {"CLASSPATH", "LDPRELOAD", "LDLIBRARYPATH", "PATH", "HOME", "PWD", "ENV", "BASHENV"}
    require(allowed, "ENV_CHANNEL", "unsupported Spring/JVM/process configuration channel")
    require(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name) is not None,
            "ENV_NAME", "unsupported environment name")
    return name


def env_value(value):
    require(type(value) is str, "ENV_VALUE", "environment values must be strings")
    require("$(" not in value and "${" not in value, "ENV_EXPANSION", "environment expansion is unsupported")
    return value


def env_reference(value, keyed):
    ref = mapping(value, "ENV_REFERENCE")
    required = {"name", "key"} if keyed else {"name"}
    require(required <= ref.keys() <= required | {"optional"}, "ENV_REFERENCE", "unsupported reference form")
    require(all(type(ref[k]) is str and bool(ref[k]) for k in required), "ENV_REFERENCE", "explicit reference required")
    exact(ref.get("optional", False), False, "ENV_REFERENCE", "optional references are unsupported")
    return ref


def config_data(index, namespace, name):
    return mapping(resource(index, "ConfigMap", namespace, name).get("data", {}), "ENV_VALUE")


def effective_environment(container, index, namespace):
    result = {}
    for source in sequence(container.get("envFrom", []), "ENV_FROM"):
        source = mapping(source, "ENV_FROM")
        require("configMapRef" in source and source.keys() <= {"configMapRef", "prefix"},
                "ENV_FROM", "only inspectable same-namespace ConfigMap envFrom is supported")
        prefix = source.get("prefix", "")
        require(type(prefix) is str, "ENV_FROM", "envFrom prefix must be a string")
        ref = env_reference(source["configMapRef"], keyed=False)
        for key, value in config_data(index, namespace, ref["name"]).items():
            result[env_name(prefix + key)] = env_value(value)
    # Kubernetes: later envFrom wins; explicit env wins over all envFrom sources.
    for name, item in named_entries(container.get("env", []), "ENV").items():
        env_name(name)
        if item.keys() == {"name", "value"}:
            result[name] = env_value(item["value"])
            continue
        require(item.keys() == {"name", "valueFrom"}, "ENV_SOURCE", "explicit value or valueFrom required")
        source = mapping(item["valueFrom"], "ENV_SOURCE")
        if source.keys() == {"configMapKeyRef"}:
            ref = env_reference(source["configMapKeyRef"], keyed=True)
            data = config_data(index, namespace, ref["name"])
            require(ref["key"] in data, "ENV_REFERENCE", "ConfigMap key is absent")
            result[name] = env_value(data[ref["key"]])
        else:
            require(source.keys() == {"secretKeyRef"} and name in SECRET_ENV,
                    "ENV_SOURCE", "only the existing unrelated explicit secret channels are supported")
            env_reference(source["secretKeyRef"], keyed=True)
            result[name] = UNINSPECTED_SECRET  # Never fetch or claim to validate secret contents.
    return result


def selector_matches(selector, labels, code):
    selector = mapping(selector, code)
    require(selector.keys() == {"matchLabels"}, code, "only the reviewed matchLabels selector is supported")
    selected = mapping(selector["matchLabels"], code)
    exact(selected.get("app.kubernetes.io/name"), "kira-backend", code, "backend selector required")
    require(all(type(v) is str and same(labels.get(k), v) for k, v in selected.items()),
            code, "selector must match backend pod labels")


def check_topology_security(deployment, pod, container, index, namespace):
    spec = deployment["spec"]
    exact(spec.get("replicas"), 2, "TOPOLOGY", "two replicas required")
    exact(spec.get("strategy"), {"type": "RollingUpdate", "rollingUpdate": {"maxUnavailable": 0, "maxSurge": 1}},
          "ROLLING", "reviewed rolling strategy required")
    labels = mapping(mapping(spec["template"].get("metadata"), "TOPOLOGY").get("labels"), "TOPOLOGY")
    selector_matches(spec.get("selector"), labels, "TOPOLOGY")
    spreads = sequence(pod.get("topologySpreadConstraints"), "TOPOLOGY")
    require(len(spreads) == 1, "TOPOLOGY", "one reviewed topology spread required")
    spread = mapping(spreads[0], "TOPOLOGY")
    exact({k: v for k, v in spread.items() if k != "labelSelector"},
          {"maxSkew": 1, "topologyKey": "kubernetes.io/hostname", "whenUnsatisfiable": "ScheduleAnyway"},
          "TOPOLOGY", "reviewed topology spread required")
    selector_matches(spread.get("labelSelector"), labels, "TOPOLOGY")
    pdb = mapping(resource(index, "PodDisruptionBudget", namespace, "kira-backend").get("spec"), "PDB")
    exact(pdb.get("minAvailable"), 1, "PDB", "PDB minAvailable must be integer one")
    require("maxUnavailable" not in pdb, "PDB", "PDB maxUnavailable is unsupported")
    selector_matches(pdb.get("selector"), labels, "PDB")

    exact(pod.get("securityContext"), POD_SECURITY, "SECURITY", "reviewed pod security required")
    exact(pod.get("automountServiceAccountToken"), False, "SECURITY", "service account token must remain disabled")
    exact(pod.get("serviceAccountName"), "kira-backend", "SECURITY", "reviewed service account required")
    for field in ("hostNetwork", "hostPID", "hostIPC", "shareProcessNamespace"):
        exact(pod.get(field, False), False, "SECURITY", "host/shared namespaces are unsupported")
    if "os" in pod:
        exact(pod["os"], {"name": "linux"}, "SECURITY", "Linux pod required")
    security = mapping(container.get("securityContext"), "SECURITY")
    inherited = {"runAsNonRoot", "runAsUser", "runAsGroup", "seccompProfile"}
    require(security.keys() <= inherited | {"allowPrivilegeEscalation", "readOnlyRootFilesystem", "capabilities", "privileged"},
            "SECURITY", "unsupported container security override")
    for field in inherited:
        exact(security.get(field, POD_SECURITY[field]), POD_SECURITY[field], "SECURITY", "unsafe effective container security")
    exact(security.get("privileged", False), False, "SECURITY", "privileged containers are unsupported")
    exact(security.get("allowPrivilegeEscalation"), False, "SECURITY", "privilege escalation must remain disabled")
    exact(security.get("readOnlyRootFilesystem"), True, "SECURITY", "read-only root required")
    capabilities = mapping(security.get("capabilities"), "SECURITY")
    require(capabilities.keys() <= {"drop", "add"}, "SECURITY", "unsupported capabilities form")
    exact(capabilities.get("drop"), ["ALL"], "SECURITY", "all capabilities must be dropped")
    exact(capabilities.get("add", []), [], "SECURITY", "additional capabilities are unsupported")

    exact(pod.get("terminationGracePeriodSeconds"), 60, "DRAIN", "reviewed termination grace required")
    exact(container.get("lifecycle"), {"preStop": {"exec": {"command": ["/bin/sh", "-c", "sleep 10"]}}},
          "DRAIN", "reviewed preStop required; not proof of writer drain")
    for name, probe in PROBES.items():
        exact(container.get(name), probe, "PROBES", "reviewed probes required")
    exact(container.get("ports"), [{"name": "http", "containerPort": 8080}, {"name": "management", "containerPort": 9090}],
          "PROBES", "reviewed probe/service ports required")
    exact(container.get("resources"), {"requests": {"cpu": "250m", "memory": "512Mi"}, "limits": {"cpu": "2", "memory": "1Gi"}},
          "RESOURCES", "resource sizing changes require a reviewed policy update")


def selected_class(value):
    if type(value) is not str or len(value) > 253:
        return False
    label = r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
    if re.fullmatch(label + r"(?:\." + label + r")*", value) is None:
        return False
    return not set(re.split(r"[.-]", value)) & {"replace", "placeholder", "example", "changeme", "unresolved", "template", "default"}


def selected_capacity(value):
    # Intentionally smaller than Kubernetes Quantity: positive whole Mi/Gi/Ti, at most int64 bytes.
    if type(value) is not str:
        return False
    match = re.fullmatch(r"([1-9][0-9]{0,12})(Mi|Gi|Ti)", value)
    return match is not None and int(match[1]) * {"Mi": 2**20, "Gi": 2**30, "Ti": 2**40}[match[2]] <= 2**63 - 1


def check_claim(pvc, mode):
    metadata = pvc["metadata"]
    exact(metadata.get("ownerReferences", []), [], "PVC_OWNER", "claim must not be garbage-collected with a workload owner")
    spec = mapping(pvc.get("spec"), "PVC_ACCESS")
    exact(spec.get("accessModes"), ["ReadWriteMany"], "PVC_ACCESS", "shared RWX access required")
    exact(spec.get("volumeMode"), "Filesystem", "PVC_ACCESS", "explicit Filesystem volume mode required")
    requests = mapping(mapping(spec.get("resources"), "STORAGE_CAPACITY").get("requests"), "STORAGE_CAPACITY")
    capacity = requests.get("storage")
    annotations = mapping(metadata.get("annotations"), "STORAGE_SELECTION")
    state = "UNRESOLVED" if mode == TEMPLATE_ONLY else "SELECTED"
    for key in (CLASS_STATE, CAPACITY_STATE):
        exact(annotations.get(key), state, "STORAGE_SELECTION", "both class and capacity selection states are required")
    if mode == TEMPLATE_ONLY:
        exact(spec.get("storageClassName"), TEMPLATE_CLASS, "STORAGE_TEMPLATE", "template class sentinel required")
        exact(capacity, TEMPLATE_CAPACITY, "STORAGE_TEMPLATE", "template capacity sentinel required")
    else:
        require(selected_class(spec.get("storageClassName")), "STORAGE_CLASS", "explicit non-placeholder storage class required")
        require(selected_capacity(capacity), "STORAGE_CAPACITY", "explicit positive whole Mi/Gi/Ti capacity required")


def check_mounts(pod, container, index, namespace, mode):
    exact(container.get("volumeDevices", []), [], "VOLUME_DEVICES", "block devices are unsupported")
    mounts = named_entries(container.get("volumeMounts", []), "MOUNT")
    volumes = named_entries(pod.get("volumes", []), "VOLUME")
    for mount in mounts.values():
        require(not {"subPath", "subPathExpr"} & mount.keys(), "MOUNT_SUBPATH", "subpath mounts are unsupported")
        require(mount.keys() <= {"name", "mountPath", "readOnly"}, "MOUNT_OPTIONS", "unsupported mount options")
    media = [m for m in mounts.values() if same(m.get("mountPath"), MEDIA_PATH)]
    scratch = [m for m in mounts.values() if same(m.get("mountPath"), "/tmp")]
    require(len(media) == 1, "MEDIA_MOUNT", "exactly one persistent media mount required")
    require(len(scratch) == 1, "SCRATCH_MOUNT", "exactly one /tmp scratch mount required")
    for mount in mounts.values():
        path = mount.get("mountPath")
        require(type(path) is str and path.startswith("/"), "MOUNT_PATH", "absolute mount path required")
        for protected in (MEDIA_PATH, "/tmp"):
            if path == protected:
                continue
            require(not (path == "/" or path.startswith(protected + "/") or protected.startswith(path.rstrip("/") + "/")),
                    "MOUNT_SHADOW", "ancestor/descendant shadow mounts are unsupported")
    require(len(mounts) == 2 and volumes.keys() == mounts.keys(), "MOUNT_SET", "only distinct media and scratch volumes/mounts are supported")
    media, scratch = media[0], scratch[0]
    exact(media.get("readOnly", False), False, "MEDIA_READ_ONLY", "media mount must be writable")
    exact(scratch.get("readOnly", False), False, "SCRATCH_READ_ONLY", "scratch mount must be writable")
    media_volume, scratch_volume = volumes[media["name"]], volumes[scratch["name"]]
    require(media_volume.keys() == {"name", "persistentVolumeClaim"}, "MEDIA_SOURCE", "media must use only the stable PVC")
    source = mapping(media_volume["persistentVolumeClaim"], "MEDIA_SOURCE")
    require(source.keys() <= {"claimName", "readOnly"}, "MEDIA_SOURCE", "ambiguous PVC source")
    exact(source.get("claimName"), CLAIM_NAME, "MEDIA_SOURCE", "stable shared claim name required")
    exact(source.get("readOnly", False), False, "MEDIA_READ_ONLY", "PVC volume source must be writable")
    require(scratch_volume.keys() == {"name", "emptyDir"}, "SCRATCH_SOURCE", "scratch must use a separate emptyDir")
    scratch_source = mapping(scratch_volume["emptyDir"], "SCRATCH_STORAGE")
    require(scratch_source.keys() <= {"medium", "sizeLimit"}, "SCRATCH_STORAGE", "unsupported emptyDir form")
    exact(scratch_source.get("medium", ""), "", "SCRATCH_STORAGE", "disk-backed default medium required")
    exact(scratch_source.get("sizeLimit"), "128Mi", "SCRATCH_STORAGE", "reviewed bounded scratch size required")
    check_claim(resource(index, "PersistentVolumeClaim", namespace, CLAIM_NAME), mode)


def check_documents(documents, mode):
    require(mode in (TEMPLATE_ONLY, RESOLVED_CONFIG), "MODE", "explicit supported policy mode required")
    index = resource_index(documents)
    deployments = [doc for (kind, _, _), doc in index.items() if kind == "Deployment"]
    require(len(deployments) == 1, "DEPLOYMENT", "exactly one reviewed backend Deployment required")
    deployment = deployments[0]
    exact(deployment["apiVersion"], "apps/v1", "DEPLOYMENT", "apps/v1 Deployment required")
    exact(deployment["metadata"]["name"], "kira-backend", "DEPLOYMENT", "backend Deployment name required")
    namespace = deployment["metadata"].get("namespace")
    require(type(namespace) is str and bool(namespace), "RESOURCE_IDENTITY", "rendered Deployment namespace required")
    spec = mapping(deployment.get("spec"), "DEPLOYMENT")
    template = mapping(spec.get("template"), "DEPLOYMENT")
    pod = mapping(template.get("spec"), "DEPLOYMENT")
    containers = named_entries(pod.get("containers"), "CONTAINER")
    require(containers.keys() == {"backend"}, "CONTAINERS", "only the reviewed backend container is supported")
    for field in ("initContainers", "ephemeralContainers"):
        exact(pod.get(field, []), [], "AUXILIARY_CONTAINERS", "init/sidecar/debug workarounds require separate review")
    container = containers["backend"]
    require(not {"command", "args", "workingDir"} & container.keys(), "PROCESS_OVERRIDE", "use the reviewed image entrypoint/working directory")
    effective = effective_environment(container, index, namespace)
    exact(effective.get(MEDIA_ENV), MEDIA_PATH, "MEDIA_ENV", "effective media directory must equal the persistent mount")
    exact(effective.get(SEED_ENV), "false", "SEED_ENV", "steady-state tutorial seed must be string false")
    exact(effective.get("SPRING_PROFILES_ACTIVE"), "prod", "ENV_CHANNEL", "only the reviewed prod profile is supported")
    check_topology_security(deployment, pod, container, index, namespace)
    check_mounts(pod, container, index, namespace, mode)


class PolicyUnitTests(unittest.TestCase):
    """All of these remain runnable by stdlib-only discovery in CI's verify job."""

    def test_type_exact_security_scalars(self):
        self.assertFalse(same(False, 0))
        self.assertFalse(same(True, 1))
        self.assertFalse(same({"replicas": True}, {"replicas": 1}))
        self.assertFalse(same("false", False))
        self.assertTrue(same({"drop": ["ALL"]}, {"drop": ["ALL"]}))

    def test_selected_storage_is_explicit_and_bounded(self):
        self.assertTrue(selected_class("synthetic-rwx"))
        self.assertTrue(selected_capacity("20Gi"))
        self.assertTrue(selected_capacity("8388607Ti"))
        for value in (None, False, 1, "", "default", TEMPLATE_CLASS, "example-rwx", "../storage"):
            with self.subTest(storage_class=value):
                self.assertFalse(selected_class(value))
        for value in (None, False, 1, "", TEMPLATE_CAPACITY, "0Gi", "-1Gi", "1.5Gi", "1e3", "8388608Ti"):
            with self.subTest(capacity=value):
                self.assertFalse(selected_capacity(value))

    def test_dictionary_env_precedence_prefix_and_secret_boundary(self):
        config = {"apiVersion": "v1", "kind": "ConfigMap", "metadata": {"name": "settings", "namespace": "kira"},
                  "data": {"TUTORIAL_SEED_ENABLED": "true"}}
        container = {"envFrom": [{"prefix": "KIRA_", "configMapRef": {"name": "settings"}}],
                     "env": [{"name": SEED_ENV, "value": "false"},
                             {"name": "KIRA_JWT_SECRET", "valueFrom": {"secretKeyRef": {"name": "secrets", "key": "jwt"}}}]}
        effective = effective_environment(container, resource_index([config]), "kira")
        self.assertEqual(effective[SEED_ENV], "false")
        self.assertIs(effective["KIRA_JWT_SECRET"], UNINSPECTED_SECRET)

    def test_resource_identity_and_optional_reference_rejections(self):
        config = {"apiVersion": "v1", "kind": "ConfigMap", "metadata": {"name": "settings", "namespace": "kira"}}
        with self.assertRaises(Refused) as caught:
            resource_index([config, copy.deepcopy(config)])
        self.assertEqual(caught.exception.code, "RESOURCE_DUPLICATE")
        with self.assertRaises(Refused) as caught:
            env_reference({"name": "settings", "optional": True}, keyed=False)
        self.assertEqual(caught.exception.code, "ENV_REFERENCE")

    def test_resolved_command_has_no_template_mode_override(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as caught:
            argument_parser().parse_args(["check-resolved", "rendered.yaml", "--mode", TEMPLATE_ONLY])
        self.assertEqual(caught.exception.code, 2)


def rendered_suite(baseline):
    """Defined only for the explicit pinned-parser invocation, never skipped at discovery."""
    check_documents(baseline, TEMPLATE_ONLY)

    def parts(documents):
        # Test helpers only. The policy above resolves real identities and named entries.
        def selected(kind, name):
            return next(doc for doc in documents if doc["kind"] == kind and doc["metadata"]["name"] == name)

        deployment = selected("Deployment", "kira-backend")
        pod = deployment["spec"]["template"]["spec"]
        backend = next(c for c in pod["containers"] if c["name"] == "backend")
        return {"deployment": deployment, "pod": pod, "backend": backend,
                "config": selected("ConfigMap", "kira-backend-config"),
                "pvc": selected("PersistentVolumeClaim", CLAIM_NAME),
                "pdb": selected("PodDisruptionBudget", "kira-backend"),
                "media_mount": next(m for m in backend["volumeMounts"] if m["mountPath"] == MEDIA_PATH),
                "scratch_mount": next(m for m in backend["volumeMounts"] if m["mountPath"] == "/tmp"),
                "media_volume": next(v for v in pod["volumes"] if "persistentVolumeClaim" in v),
                "scratch_volume": next(v for v in pod["volumes"] if "emptyDir" in v)}

    resolved = copy.deepcopy(baseline)
    pvc = parts(resolved)["pvc"]
    pvc["metadata"]["annotations"].update({CLASS_STATE: "SELECTED", CAPACITY_STATE: "SELECTED"})
    pvc["spec"]["storageClassName"] = "synthetic-rwx"
    pvc["spec"]["resources"]["requests"]["storage"] = "20Gi"

    class RenderedMediaTests(unittest.TestCase):
        def reject(self, mutation, code, source=None, mode=RESOLVED_CONFIG):
            documents = copy.deepcopy(resolved if source is None else source)
            mutation(documents, parts(documents))
            with self.assertRaises(Refused) as caught:
                check_documents(documents, mode)
            self.assertEqual(caught.exception.code, code)

        def test_actual_template_and_synthetic_resolved_positive_controls(self):
            check_documents(copy.deepcopy(baseline), TEMPLATE_ONLY)
            check_documents(copy.deepcopy(resolved), RESOLVED_CONFIG)
            self.reject(lambda d, p: None, "STORAGE_SELECTION", source=baseline)
            self.reject(lambda d, p: None, "STORAGE_SELECTION", mode=TEMPLATE_ONLY)
            reordered = copy.deepcopy(resolved)
            reordered.reverse()
            p = parts(reordered)
            p["pod"]["volumes"].reverse()
            p["backend"]["volumeMounts"].reverse()
            p["backend"]["securityContext"].update(runAsUser=10001, runAsGroup=10001, runAsNonRoot=True,
                                                   seccompProfile={"type": "RuntimeDefault"})
            check_documents(reordered, RESOLVED_CONFIG)

        def test_parser_front_end_rejections(self):
            cases = [
                ("key: first\nkey: second\n", "YAML_DUPLICATE_KEY"),
                ("key: !unsupported value\n", "YAML_INVALID"),
                ("key: [unterminated\n", "YAML_INVALID"),
                ("1: value\n", "YAML_KEY_TYPE"),
                ("true: value\n", "YAML_KEY_TYPE"),
                ("? [a, b]\n: value\n", "YAML_KEY_TYPE"),
                ("a: &anchor value\nb: *anchor\n", "YAML_ALIAS"),
                ("item:\n  <<: {a: value}\n", "YAML_MERGE"),
            ]
            for raw, code in cases:
                with self.subTest(code=code, raw=raw):
                    with self.assertRaises(Refused) as caught:
                        load_rendered(raw)
                    self.assertEqual(caught.exception.code, code)
            for raw in ("", "---\n", "- not-a-resource\n", "scalar\n", "apiVersion: v1\nkind: ConfigMap\n"):
                with self.subTest(shape=raw):
                    with self.assertRaises(Refused) as caught:
                        check_documents(load_rendered(raw), TEMPLATE_ONLY)
                    self.assertEqual(caught.exception.code, "RESOURCE_SHAPE")

        def test_resource_and_named_entry_rejections(self):
            for kind in ("deployment", "config", "pvc", "pdb"):
                with self.subTest(duplicate=kind):
                    self.reject(lambda d, p: d.append(copy.deepcopy(p[kind])), "RESOURCE_DUPLICATE")
                with self.subTest(missing=kind):
                    self.reject(lambda d, p: d.remove(p[kind]), "DEPLOYMENT" if kind == "deployment" else "RESOURCE_REQUIRED")
            for kind in ("config", "pvc", "pdb"):
                with self.subTest(namespace=kind):
                    self.reject(lambda d, p: p[kind]["metadata"].update(namespace="other"), "RESOURCE_REQUIRED")
                with self.subTest(name=kind):
                    self.reject(lambda d, p: p[kind]["metadata"].update(name="wrong"), "RESOURCE_REQUIRED")
                with self.subTest(implicit_namespace=kind):
                    self.reject(lambda d, p: p[kind]["metadata"].pop("namespace"), "RESOURCE_IDENTITY")
            self.reject(lambda d, p: p["deployment"]["metadata"].update(name="wrong"), "DEPLOYMENT")
            self.reject(lambda d, p: p["deployment"]["metadata"].pop("namespace"), "RESOURCE_IDENTITY")
            self.reject(lambda d, p: p["pod"]["containers"].append(copy.deepcopy(p["backend"])), "CONTAINER_DUPLICATE")
            self.reject(lambda d, p: p["backend"].update(name="wrong"), "CONTAINERS")
            self.reject(lambda d, p: p["pod"].update(containers=[]), "CONTAINERS")
            self.reject(lambda d, p: p["pod"]["volumes"].append(copy.deepcopy(p["media_volume"])), "VOLUME_DUPLICATE")
            self.reject(lambda d, p: p["backend"]["volumeMounts"].append(copy.deepcopy(p["media_mount"])), "MOUNT_DUPLICATE")

        def test_supported_effective_env_forms(self):
            for form in ("ordered", "prefix", "literal", "key"):
                with self.subTest(form=form):
                    documents = copy.deepcopy(resolved)
                    p = parts(documents)
                    extra = copy.deepcopy(p["config"])
                    extra["metadata"]["name"] = "extra-settings"
                    extra["data"] = {MEDIA_ENV: MEDIA_PATH, SEED_ENV: "false"}
                    documents.append(extra)
                    if form == "ordered":
                        extra["data"][MEDIA_ENV] = "/tmp/not-durable"
                        p["backend"]["envFrom"].insert(0, {"configMapRef": {"name": "extra-settings"}})
                    elif form == "prefix":
                        extra["data"] = {"MEDIA_DIRECTORY": MEDIA_PATH, "SEED_ENABLED": "false"}
                        p["backend"]["envFrom"].append({"prefix": "KIRA_TUTORIAL_", "configMapRef": {"name": "extra-settings"}})
                    else:
                        p["config"]["data"].update({MEDIA_ENV: "/tmp/not-durable", SEED_ENV: "true"})
                        for name, value in ((MEDIA_ENV, MEDIA_PATH), (SEED_ENV, "false")):
                            source = {"value": value} if form == "literal" else {
                                "valueFrom": {"configMapKeyRef": {"name": "extra-settings", "key": name}}}
                            p["backend"]["env"].append({"name": name, **source})
                    check_documents(documents, RESOLVED_CONFIG)

        def test_effective_env_rejections(self):
            for key, value, code in ((MEDIA_ENV, "/tmp/not-durable", "MEDIA_ENV"), (SEED_ENV, "true", "SEED_ENV")):
                with self.subTest(later_override=key):
                    def later_source(documents, p):
                        extra = copy.deepcopy(p["config"])
                        extra["metadata"]["name"] = "later-settings"
                        extra["data"] = {key: value}
                        documents.append(extra)
                        p["backend"]["envFrom"].append({"configMapRef": {"name": "later-settings"}})
                    self.reject(later_source, code)
            self.reject(lambda d, p: p["backend"]["env"].append({"name": MEDIA_ENV, "value": "/tmp/not-durable"}), "MEDIA_ENV")
            self.reject(lambda d, p: p["backend"]["env"].append({"name": SEED_ENV, "value": "true"}), "SEED_ENV")
            self.reject(lambda d, p: p["backend"]["env"].append(copy.deepcopy(p["backend"]["env"][0])), "ENV_DUPLICATE")
            for value, code in ((False, "ENV_VALUE"), ("true", "SEED_ENV"), ("False", "SEED_ENV"), ("$(SEED)", "ENV_EXPANSION")):
                with self.subTest(seed=value):
                    self.reject(lambda d, p: p["config"]["data"].update({SEED_ENV: value}), code)
            self.reject(lambda d, p: p["config"]["data"].pop(MEDIA_ENV), "MEDIA_ENV")
            self.reject(lambda d, p: p["config"]["data"].update({MEDIA_ENV: "${MEDIA_ROOT}/media"}), "ENV_EXPANSION")
            self.reject(lambda d, p: p["backend"]["envFrom"].append({"secretRef": {"name": "uninspectable"}}), "ENV_FROM")
            self.reject(lambda d, p: p["backend"]["envFrom"][0]["configMapRef"].update(optional=True), "ENV_REFERENCE")
            for ref, code in (({"name": "absent", "key": MEDIA_ENV}, "RESOURCE_REQUIRED"),
                              ({"name": "kira-backend-config", "key": "absent"}, "ENV_REFERENCE"),
                              ({"name": "kira-backend-config", "key": MEDIA_ENV, "optional": True}, "ENV_REFERENCE")):
                with self.subTest(reference=ref):
                    self.reject(lambda d, p: p["backend"]["env"].append({"name": MEDIA_ENV, "valueFrom": {"configMapKeyRef": ref}}), code)
            self.reject(lambda d, p: p["backend"]["env"].append({"name": MEDIA_ENV, "valueFrom": {"secretKeyRef": {"name": "secret", "key": "path"}}}), "ENV_SOURCE")
            self.reject(lambda d, p: p["backend"]["env"].append({"name": MEDIA_ENV, "valueFrom": {"fieldRef": {"fieldPath": "metadata.name"}}}), "ENV_SOURCE")

        def test_unsupported_configuration_channels(self):
            names = ("SPRING_APPLICATION_JSON", "KIRA_TUTORIAL_MEDIADIRECTORY", "KIRA_TUTORIAL_SEEDENABLED",
                     "kira.tutorial.media-directory", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
                     "JAVA_OPTS", "SPRING_CONFIG_LOCATION", "SPRING_CONFIG_ADDITIONAL_LOCATION",
                     "SPRING_CONFIG_IMPORT", "SPRING_CONFIG_NAME", "SPRING_PROFILES_INCLUDE", "LOADER_PATH", "PATH")
            for name in names:
                with self.subTest(channel=name):
                    self.reject(lambda d, p: p["backend"]["env"].append({"name": name, "value": "override"}), "ENV_CHANNEL")
            self.reject(lambda d, p: p["config"]["data"].update(SPRING_APPLICATION_JSON="{}"), "ENV_CHANNEL")
            for field, value in (("command", ["java"]), ("args", ["--kira.tutorial.seed-enabled=true"]), ("workingDir", MEDIA_PATH)):
                with self.subTest(process=field):
                    self.reject(lambda d, p: p["backend"].update({field: value}), "PROCESS_OVERRIDE")

        def test_persistent_media_chain_rejections(self):
            for access in (["ReadWriteOnce"], ["ReadWriteOncePod"], ["ReadOnlyMany"], ["ReadWriteMany", "ReadWriteOnce"]):
                with self.subTest(access=access):
                    self.reject(lambda d, p: p["pvc"]["spec"].update(accessModes=access), "PVC_ACCESS")
            for volume_mode in (None, "Block"):
                with self.subTest(volume_mode=volume_mode):
                    self.reject(lambda d, p: p["pvc"]["spec"].update(volumeMode=volume_mode), "PVC_ACCESS")
            for value in (True, "false", 0):
                with self.subTest(read_only=value, surface="mount"):
                    self.reject(lambda d, p: p["media_mount"].update(readOnly=value), "MEDIA_READ_ONLY")
                with self.subTest(read_only=value, surface="source"):
                    self.reject(lambda d, p: p["media_volume"]["persistentVolumeClaim"].update(readOnly=value), "MEDIA_READ_ONLY")
            self.reject(lambda d, p: p["media_mount"].update(mountPath="/tmp/kira/tutorial-media"), "MEDIA_MOUNT")
            self.reject(lambda d, p: p["media_volume"]["persistentVolumeClaim"].update(claimName="per-pod-claim"), "MEDIA_SOURCE")
            for source in ({"emptyDir": {}}, {"hostPath": {"path": MEDIA_PATH}}, {"ephemeral": {"volumeClaimTemplate": {}}}):
                with self.subTest(source=source):
                    def replace(documents, p):
                        name = p["media_volume"]["name"]
                        p["media_volume"].clear()
                        p["media_volume"].update(name=name, **source)
                    self.reject(replace, "MEDIA_SOURCE")
            self.reject(lambda d, p: p["media_volume"].update(emptyDir={}), "MEDIA_SOURCE")
            self.reject(lambda d, p: p["pod"]["volumes"].remove(p["media_volume"]), "MOUNT_SET")

            def media_on_scratch(documents, p):
                media_name, scratch_name = p["media_mount"]["name"], p["scratch_mount"]["name"]
                p["media_mount"]["name"], p["scratch_mount"]["name"] = scratch_name, media_name

            self.reject(media_on_scratch, "MEDIA_SOURCE")
            for field in ("subPath", "subPathExpr"):
                with self.subTest(field=field):
                    self.reject(lambda d, p: p["media_mount"].update({field: "pod"}), "MOUNT_SUBPATH")
            self.reject(lambda d, p: p["backend"].update(volumeDevices=[{"name": "tutorial-media", "devicePath": "/dev/media"}]), "VOLUME_DEVICES")
            for path in ("/", "/var/lib/kira", MEDIA_PATH + "/nested", "/tmp/nested"):
                with self.subTest(shadow=path):
                    self.reject(lambda d, p: p["backend"]["volumeMounts"].append({"name": "shadow", "mountPath": path}), "MOUNT_SHADOW")
            self.reject(lambda d, p: p["backend"]["volumeMounts"].append({"name": "external-config", "mountPath": "/app/config"}), "MOUNT_SET")
            self.reject(lambda d, p: p["pvc"]["metadata"].update(ownerReferences=[{"apiVersion": "apps/v1", "kind": "Deployment", "name": "kira-backend", "uid": "synthetic"}]), "PVC_OWNER")

        def test_scratch_rejections(self):
            self.reject(lambda d, p: p["backend"]["volumeMounts"].remove(p["scratch_mount"]), "SCRATCH_MOUNT")
            self.reject(lambda d, p: p["scratch_mount"].update(name=p["media_mount"]["name"]), "MOUNT_DUPLICATE")
            self.reject(lambda d, p: p["scratch_mount"].update(readOnly=True), "SCRATCH_READ_ONLY")
            self.reject(lambda d, p: p["scratch_volume"].update(persistentVolumeClaim={"claimName": CLAIM_NAME}), "SCRATCH_SOURCE")
            for empty_dir in ({}, {"sizeLimit": "128Mi", "medium": "Memory"}, {"sizeLimit": "256Mi"}, {"sizeLimit": 0}, {"sizeLimit": None}):
                with self.subTest(empty_dir=empty_dir):
                    self.reject(lambda d, p: p["scratch_volume"].update(emptyDir=empty_dir), "SCRATCH_STORAGE")
            self.reject(lambda d, p: p["pod"]["volumes"].remove(p["scratch_volume"]), "MOUNT_SET")

        def test_security_and_topology_rejections(self):
            for field, value in (("runAsUser", 0), ("runAsGroup", 0), ("runAsNonRoot", False),
                                 ("readOnlyRootFilesystem", False), ("readOnlyRootFilesystem", "true"),
                                 ("privileged", True), ("allowPrivilegeEscalation", True),
                                 ("seccompProfile", {"type": "Unconfined"}),
                                 ("capabilities", {"drop": ["ALL"], "add": ["CHOWN"]})):
                with self.subTest(security=field, value=value):
                    self.reject(lambda d, p: p["backend"]["securityContext"].update({field: value}), "SECURITY")
            self.reject(lambda d, p: p["pod"]["securityContext"].update(fsGroup=0), "SECURITY")
            self.reject(lambda d, p: p["pod"].update(initContainers=[{"name": "chown", "image": "synthetic"}]), "AUXILIARY_CONTAINERS")
            self.reject(lambda d, p: p["pod"]["containers"].append({"name": "sidecar", "image": "synthetic"}), "CONTAINERS")
            for replicas in (1, True, "2"):
                with self.subTest(replicas=replicas):
                    self.reject(lambda d, p: p["deployment"]["spec"].update(replicas=replicas), "TOPOLOGY")
            for minimum in (0, True, "1"):
                with self.subTest(pdb=minimum):
                    self.reject(lambda d, p: p["pdb"]["spec"].update(minAvailable=minimum), "PDB")
            for unavailable in (1, False, "0"):
                with self.subTest(rolling=unavailable):
                    self.reject(lambda d, p: p["deployment"]["spec"]["strategy"]["rollingUpdate"].update(maxUnavailable=unavailable), "ROLLING")
            self.reject(lambda d, p: p["pod"].update(topologySpreadConstraints=[]), "TOPOLOGY")
            self.reject(lambda d, p: p["backend"]["readinessProbe"]["httpGet"].update(path="/"), "PROBES")
            self.reject(lambda d, p: p["backend"].pop("lifecycle"), "DRAIN")
            self.reject(lambda d, p: p["backend"].pop("resources"), "RESOURCES")

        def test_explicit_class_and_capacity_selection(self):
            for key in (CLASS_STATE, CAPACITY_STATE):
                with self.subTest(missing_marker=key):
                    self.reject(lambda d, p: p["pvc"]["metadata"]["annotations"].pop(key), "STORAGE_SELECTION")
                with self.subTest(unresolved_marker=key):
                    self.reject(lambda d, p: p["pvc"]["metadata"]["annotations"].update({key: "UNRESOLVED"}), "STORAGE_SELECTION")
            for value in (None, "", "default", "example-rwx", TEMPLATE_CLASS, "INVALID_CLASS", False):
                with self.subTest(storage_class=value):
                    self.reject(lambda d, p: p["pvc"]["spec"].update(storageClassName=value), "STORAGE_CLASS")
            self.reject(lambda d, p: p["pvc"]["spec"].pop("storageClassName"), "STORAGE_CLASS")
            for value in (None, "", "0Gi", "-1Gi", "1.5Gi", "1e3", TEMPLATE_CAPACITY, False, 20):
                with self.subTest(capacity=value):
                    self.reject(lambda d, p: p["pvc"]["spec"]["resources"]["requests"].update(storage=value), "STORAGE_CAPACITY")
            self.reject(lambda d, p: p["pvc"]["spec"]["resources"]["requests"].pop("storage"), "STORAGE_CAPACITY")
            self.reject(lambda d, p: p["pvc"]["spec"].update(storageClassName="synthetic-rwx"), "STORAGE_TEMPLATE", source=baseline, mode=TEMPLATE_ONLY)
            self.reject(lambda d, p: p["pvc"]["spec"]["resources"]["requests"].update(storage="20Gi"), "STORAGE_TEMPLATE", source=baseline, mode=TEMPLATE_ONLY)

    return unittest.TestSuite([
        unittest.defaultTestLoader.loadTestsFromTestCase(PolicyUnitTests),
        unittest.defaultTestLoader.loadTestsFromTestCase(RenderedMediaTests),
    ])


def argument_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("check-template", "check-resolved", "test-rendered"):
        commands.add_parser(name).add_argument("rendered", type=Path)
    return parser


def main(argv=None):
    options = argument_parser().parse_args(argv)
    try:
        documents = load_rendered(options.rendered.read_text(encoding="utf-8"))
        if options.command == "test-rendered":
            result = unittest.TextTestRunner(verbosity=2).run(rendered_suite(documents))
            return 0 if result.wasSuccessful() else 1
        mode = TEMPLATE_ONLY if options.command == "check-template" else RESOLVED_CONFIG
        check_documents(documents, mode)
        print(mode + ": configuration checks only; installed storage/bootstrap/recovery NOT verified.")
        if mode == TEMPLATE_ONLY:
            print("UNRESOLVED TEMPLATE: not rollout admission. Never apply the base.")
        return 0
    except Refused as exc:
        print(exc.code + ": " + str(exc), file=sys.stderr)
        return 1
    except (OSError, UnicodeError):
        print("INPUT: could not read rendered UTF-8 YAML", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
