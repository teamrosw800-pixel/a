#!/usr/bin/env python3
# Mutation experiment for the Stake 2.0 revert tests (see STAKE_V2_MUTATION_REPORT.md).
#
# USAGE (from the repository root):  python3 java-tron/mutation/stake_v2_revert_mutants.py [ID ...]
# It edits main sources TEMPORARILY: each mutant is applied, the chosen tests are run, and the file is
# restored with `git checkout` in a `finally`. NEVER commit while it is running. It refuses to start
# if tracked files are already modified. Predictions are fixed in this file before any run.
#
# Executed version, with only the path constants adapted (REPO, S, JAVA_HOME) and the M5 defect commented.
"""Mutation experiment for the Stake 2.0 revert tests.

For each mutant: apply an exact text replacement to one main-source file, run the chosen test
classes with Gradle, parse the JUnit XML, then ALWAYS restore the file with `git checkout` and
check that the tracked tree is clean. Predictions are fixed here, BEFORE any run.
"""
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # the java-tron directory
S = os.environ.get("MUTANT_OUT", os.path.join(REPO, "mutation", "out"))  # logs and results
os.makedirs(S, exist_ok=True)
RESULTS = os.path.join(REPO, "framework/build/test-results/test")
PKG = "org.tron.common.runtime.vm."
ENV = dict(os.environ)
ENV.setdefault("JAVA_HOME", "/usr/lib/jvm/java-8-openjdk-amd64")  # the build needs Java 8
ENV["PATH"] = ENV["JAVA_HOME"] + "/bin:" + ENV["PATH"]

ALL = ["FreezeV2RevertTest", "NestedFreezeV2RevertTest", "DelegateResourceRevertTest",
       "UnDelegateResourceRevertTest", "NestedStakeV2RevertTest", "UnfreezeV2RevertTest",
       "NestedUnfreezeV2RevertTest", "WithdrawAndCancelUnfreezeV2RevertTest"]

ACT = "actuator/src/main/java/org/tron/core/"
CB = "org.tron.core.ChainBaseManager.getInstance()"


def has(m, *subs):
    return any(s in m for s in subs)


# Each mutant: id, description, file, [(old, new, expected_count)], classes, predict(cls, method)->bool
MUTANTS = [
    dict(
        id="M0", desc="EQUIVALENT: change a debug log message only (control, must change nothing)",
        file=ACT + "vm/program/Program.java",
        edits=[('"contract run halted by Exception: contract: [{}], exception: [{}]"',
                '"contract run halted (mutant M0): contract: [{}], exception: [{}]"', 2)],
        classes=["FreezeV2RevertTest", "NestedFreezeV2RevertTest"],
        predict=lambda c, m: False),
    dict(
        id="M1", desc="top level: commit the root repository even when the transaction REVERTs",
        file=ACT + "actuator/VMActuator.java",
        edits=[('            result.setRuntimeError("REVERT opcode executed");',
                '            rootRepository.commit(); // MUTANT M1\n'
                '            result.setRuntimeError("REVERT opcode executed");', 1)],
        classes=ALL,
        # every test whose transaction ends in REVERT fails; the inner-reverts shape ends in SUCCESS
        predict=lambda c, m: not has(m, "Inner")),
    dict(
        id="M2", desc="nested: merge (commit) the callee frame into the caller even when it reverted",
        file=ACT + "vm/program/Program.java",
        edits=[('        callResult.rejectInternalTransactions();\n\n        stackPushZero();',
                '        callResult.rejectInternalTransactions();\n'
                '        if (callResult.getException() == null) {\n'
                '          deposit.commit(); // MUTANT M2\n'
                '        }\n\n        stackPushZero();', 1)],
        classes=ALL,
        # only where a called frame reverts while the transaction goes on: the inner-reverts shape
        predict=lambda c, m: has(m, "Inner")),
    dict(
        id="M3", desc="freeze: bandwidth global weight written straight to the store",
        file=ACT + "vm/nativecontract/FreezeBalanceV2Processor.java",
        edits=[('        repo.addTotalNetWeight(newNetWeight - oldNetWeight);',
                '        ' + CB + '.getDynamicPropertiesStore().addTotalNetWeight('
                'newNetWeight - oldNetWeight); // MUTANT M3', 1)],
        classes=["FreezeV2RevertTest", "NestedFreezeV2RevertTest", "UnfreezeV2RevertTest"],
        predict=lambda c, m: c == "FreezeV2RevertTest" or (
            c == "NestedFreezeV2RevertTest" and m.startswith("bandwidth"))),
    dict(
        id="M4", desc="unfreeze: energy global weight written straight to the store",
        file=ACT + "vm/nativecontract/UnfreezeBalanceV2Processor.java",
        edits=[('        repo.addTotalEnergyWeight(newEnergyWeight - oldEnergyWeight);',
                '        ' + CB + '.getDynamicPropertiesStore().addTotalEnergyWeight('
                'newEnergyWeight - oldEnergyWeight); // MUTANT M4', 1)],
        classes=["UnfreezeV2RevertTest", "NestedUnfreezeV2RevertTest",
                 "WithdrawAndCancelUnfreezeV2RevertTest"],
        predict=lambda c, m: (c == "UnfreezeV2RevertTest" and m.startswith("energyUnfreeze")) or (
            c == "NestedUnfreezeV2RevertTest" and m.startswith("legacyEnergy"))),
    dict(
        id="M5", desc="unfreeze: VotesStore record written straight to the store",
        file=ACT + "vm/nativecontract/UnfreezeBalanceV2Processor.java",
        edits=[('repo.updateVotes(ownerAddress, votesCapsule);',
                CB + '.getVotesStore().put(ownerAddress, votesCapsule); // MUTANT M5', 2)],
        classes=["UnfreezeV2RevertTest", "NestedUnfreezeV2RevertTest"],
        # KNOWN DEFECT, kept as executed: "Legacy" is case-sensitive but the two nested tests are
        # named "legacyEnergyUnfreeze...", so they were NOT predicted here although the written
        # prediction included them. The run reported them as 2 "unexpected failures". The intended
        # predicate is: has(m, "TronPower", "legacy", "Legacy")
        predict=lambda c, m: has(m, "TronPower", "Legacy")),
    dict(
        id="M6", desc="reward settlement: begin cycle (no-votes branch) written straight to the store",
        file=ACT + "vm/utils/VoteRewardUtil.java",
        edits=[('      repository.updateBeginCycle(address, endCycle + 1);',
                '      ' + CB + '.getDelegationStore().setBeginCycle(address, endCycle + 1);'
                ' // MUTANT M6', 1)],
        classes=["UnfreezeV2RevertTest", "NestedUnfreezeV2RevertTest"],
        predict=lambda c, m: m.startswith("first")),
    dict(
        id="M7", desc="undelegate: the two index deletions done straight on the store",
        file=ACT + "vm/nativecontract/UnDelegateResourceProcessor.java",
        edits=[('      repo.updateDelegatedResourceAccountIndex(\n'
                '          fromKey, new DelegatedResourceAccountIndexCapsule(new byte[0]));',
                '      ' + CB + '.getDelegatedResourceAccountIndexStore().delete(fromKey);'
                ' // MUTANT M7a', 1),
               ('      repo.updateDelegatedResourceAccountIndex(\n'
                '          toKey, new DelegatedResourceAccountIndexCapsule(new byte[0]));',
                '      ' + CB + '.getDelegatedResourceAccountIndexStore().delete(toKey);'
                ' // MUTANT M7b', 1)],
        classes=["UnDelegateResourceRevertTest", "NestedStakeV2RevertTest"],
        predict=lambda c, m: m.startswith("full") and (
            c == "UnDelegateResourceRevertTest" or "UnDelegate" in m)),
    dict(
        id="M8", desc="withdraw: the account written straight to the store",
        file=ACT + "vm/nativecontract/WithdrawExpireUnfreezeProcessor.java",
        edits=[('    repo.updateAccount(ownerCapsule.createDbKey(), ownerCapsule);',
                '    ' + CB + '.getAccountStore().put(ownerCapsule.createDbKey(), ownerCapsule);'
                ' // MUTANT M8', 1)],
        classes=["WithdrawAndCancelUnfreezeV2RevertTest"],
        predict=lambda c, m: m.startswith("withdraw")),
    dict(
        id="M9", desc="delegate: the delegation record written straight to the store",
        file=ACT + "vm/nativecontract/DelegateResourceProcessor.java",
        edits=[('    repo.updateDelegatedResource(key, delegatedResourceCapsule);',
                '    ' + CB + '.getDelegatedResourceStore().put(key, delegatedResourceCapsule);'
                ' // MUTANT M9', 1)],
        classes=["DelegateResourceRevertTest", "NestedStakeV2RevertTest",
                 "UnDelegateResourceRevertTest"],
        predict=lambda c, m: c == "DelegateResourceRevertTest" or (
            c == "NestedStakeV2RevertTest" and m.startswith("delegate"))),
    dict(
        id="M10", desc="cancel-all: tron power global weight written straight to the store",
        file=ACT + "vm/nativecontract/CancelAllUnfreezeV2Processor.java",
        edits=[('        repo.addTotalTronPowerWeight(newTPWeight - oldTPWeight);',
                '        ' + CB + '.getDynamicPropertiesStore().addTotalTronPowerWeight('
                'newTPWeight - oldTPWeight); // MUTANT M10', 1)],
        classes=["WithdrawAndCancelUnfreezeV2RevertTest"],
        predict=lambda c, m: m.startswith("cancel")),
]


MUTANTS.append(dict(
    id="M8b", desc="withdraw: the account written through the repository AND ALSO straight to the "
                   "store (in-execution reads stay correct; only a post-revert comparison can see it)",
    file=ACT + "vm/nativecontract/WithdrawExpireUnfreezeProcessor.java",
    edits=[('    repo.updateAccount(ownerCapsule.createDbKey(), ownerCapsule);',
            '    repo.updateAccount(ownerCapsule.createDbKey(), ownerCapsule);\n'
            '    ' + CB + '.getAccountStore().put(ownerCapsule.createDbKey(), ownerCapsule);'
            ' // MUTANT M8b', 1)],
    classes=["WithdrawAndCancelUnfreezeV2RevertTest"],
    predict=lambda c, m: m.startswith("withdraw")))


def sh(cmd, **kw):
    return subprocess.run(cmd, cwd=REPO, env=ENV, capture_output=True, text=True, **kw)


def tracked_dirty():
    out = sh(["git", "status", "--porcelain", "--untracked-files=no"]).stdout.strip()
    return out


def parse_results():
    """Return {(class, method): (failed: bool, first failure message)}."""
    res = {}
    if not os.path.isdir(RESULTS):
        return res
    for f in os.listdir(RESULTS):
        if not f.endswith(".xml"):
            continue
        root = ET.parse(os.path.join(RESULTS, f)).getroot()
        cls = root.get("name").replace(PKG, "")
        for tc in root.findall("testcase"):
            m = tc.get("name")
            bad = tc.find("failure")
            if bad is None:
                bad = tc.find("error")
            key = (cls, m)
            prev = res.get(key, (False, ""))
            if bad is not None:
                msg = (bad.get("message") or "").split("\n")[0][:230]
                res[key] = (True, prev[1] or msg)
            else:
                res[key] = (prev[0], prev[1])
    return res


def progress(line):
    with open(os.path.join(S, "mutants.progress"), "a") as p:
        p.write(line + "\n")


def run_mutant(mu):
    path = os.path.join(REPO, mu["file"])
    src = open(path).read()
    mutated = src
    for old, new, cnt in mu["edits"]:
        found = mutated.count(old)
        if found != cnt:
            return dict(id=mu["id"], status="NOT_APPLIED", detail="expected %d found %d for %r"
                        % (cnt, found, old[:70]))
        mutated = mutated.replace(old, new)
    t0 = time.time()
    try:
        open(path, "w").write(mutated)
        cmd = ["./gradlew", "--no-daemon", "--max-workers=1", ":framework:test"]
        for c in mu["classes"]:
            cmd += ["--tests", PKG + c]
        proc = sh(cmd, timeout=1500)
        log = proc.stdout + proc.stderr
        open(os.path.join(S, "mutant_%s.log" % mu["id"]), "w").write(log)
    finally:
        sh(["git", "checkout", "--", mu["file"]])
    dirty = tracked_dirty()
    res = parse_results()
    compiled = any(cls in mu["classes"] for (cls, _) in res)
    rows = []
    for (cls, m), (failed, msg) in sorted(res.items()):
        if cls not in mu["classes"]:
            continue
        pred = bool(mu["predict"](cls, m))
        rows.append(dict(cls=cls, method=m, failed=failed, predicted=pred, msg=msg))
    killed = [r for r in rows if r["failed"]]
    return dict(
        id=mu["id"], desc=mu["desc"], status="RAN" if compiled else "NO_RESULTS",
        gradle_exit=proc.returncode, seconds=round(time.time() - t0),
        env_429=("status code 429" in log), tree_clean_after=(dirty == ""),
        tests=len(rows), failed=len(killed),
        unexpected_failures=[r for r in rows if r["failed"] and not r["predicted"]],
        unexpected_passes=[r for r in rows if (not r["failed"]) and r["predicted"]],
        failing=[(r["cls"], r["method"], r["msg"]) for r in killed])


def main():
    only = sys.argv[1:]
    assert tracked_dirty() == "", "tracked tree not clean before start: " + tracked_dirty()
    out = os.path.join(S, "mutants.json")
    results = json.load(open(out)) if (only and os.path.exists(out)) else {}
    for mu in MUTANTS:
        if only and mu["id"] not in only:
            continue
        progress("START %s %s" % (mu["id"], mu["desc"]))
        r = run_mutant(mu)
        results[mu["id"]] = r
        json.dump(results, open(out, "w"), indent=1)
        progress("DONE %s status=%s tests=%s failed=%s unexpected_fail=%s unexpected_pass=%s "
                 "clean=%s exit=%s %ss" % (
                     mu["id"], r.get("status"), r.get("tests"), r.get("failed"),
                     len(r.get("unexpected_failures", [])), len(r.get("unexpected_passes", [])),
                     r.get("tree_clean_after"), r.get("gradle_exit"), r.get("seconds")))
    progress("ALL_DONE")


if __name__ == "__main__":
    main()
