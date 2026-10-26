# Scheduled reporting and threshold alerts

This integration pattern separates a report's business query, schedule, optional AI
explanation and delivery channel. DB Companion can inspect supported Oracle Scheduler
and Agent metadata; this guide does not install a reporting job, email service or
Slack sender. Operational configuration and business queries belong to each deployment.

## Select the execution path

A fixed threshold alert should run reviewed SQL and evaluate its threshold
deterministically. It does not need an AI profile. An Agent-based narrative report
also needs an explicitly configured Team and Agent profile; any query tool's profile
must be recorded separately. A scheduler job name alone does not establish which
model, profile or SQL produced a report.

Record the owner, job action, schedule and time zone alongside the configured Team,
profiles, business-query version and destination identifier. Keep passwords, Wallets,
recipient lists and webhook URLs outside source control. Do not infer permissions
from an account being called ADMIN; inspect the privileges required by each operation.

## Define the business period and threshold

Choose the reporting day in the business time zone and check that the source load is
complete. Production daily mode should use the intended business day, not silently
substitute the latest available sample. Distinguish missing data, a genuine zero and
a failed query.

For example, a configurable alert might compare a day's value with the mean of the
preceding four complete days and notify on an absolute change of at least 20 percent.
These are example parameters, not built-in defaults. Define zero-baseline handling,
missing-day handling and whether the threshold is inclusive before enabling delivery.
A latest-available-data demonstration mode must be visibly separate from production;
never represent a threshold-bypassing test notification as a genuine alert.

## Deliver and audit

Use a run key containing the report, business day and destination to prevent duplicate
delivery. Preserve the reviewed SQL identity, source freshness, metric values,
threshold decision, start/end time and delivery state. Limit records and access to
the approved operational purpose. An accepted HTTP request is not proof that a person
received or read a report. If delivery times out with an unknown outcome, reconcile
its state before retrying; do not silently send duplicates.

For non-SQL users, display the reporting period, target population, filters, metric
definitions and units alongside the result. Keep SQL and detailed provenance under
an expandable section. Label query execution, business-rule review and delivery as
separate states. An AI explanation must not turn an unverified result into a
certified business answer.

## Validate before enabling the schedule

Use a disabled job and a non-delivering dry run first. Check ordinary values,
threshold boundaries, missing/zero baselines, delayed source loads and time-zone
boundaries. With separate authorization, test one delivery to an approved test
destination and verify duplicate prevention and failure reporting. Enable the
production schedule only after the business owner accepts the query and rules.
