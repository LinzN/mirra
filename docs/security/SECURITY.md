# Mirra – Security Documentation

| | |
|---|---|
| Component | `mirra` STEM plugin (AI agent on the OpenAI Responses API) |
| Version covered | `master` as of 2026-10-26 (commit `2bce4a4`) |
| Depends on | STEM `master` from `69b2e69`, evolution-api-java 0.0.5, home-devices |
| Audience | Owner and administrators of the MirraNET installation |

This document describes what Mirra is able to do, which permissions control it, which data she processes and
where it goes, and it contains a risk analysis with the remaining risks and recommended hardening steps.

---

## 1. Overview

Mirra is an AI agent that runs inside STEM, the smart home framework of the household. People talk to her on
Discord (private messages) and WhatsApp (Evolution API). For every message Mirra sends the conversation to the
OpenAI Responses API together with a list of tools. The model decides which tools to call; Mirra executes them
locally in STEM and returns the results to the model until it answers.

**The model never executes anything itself. Every action goes through a tool, and every tool checks the
permissions of the person Mirra is talking to before it is offered to the model and again before it runs.**

```
Discord / WhatsApp ──► identity + access control ──► MirraAgent ──► OpenAI (model)
                                                          │              │ tool calls
                                                          ▼              ▼
                                         ToolRegistry (permission check) ──► STEM API, home-devices,
                                                                             shell/SSH, database, cloud,
                                                                             MQTT, messaging
```

Besides chats, Mirra also acts on her own:

- **Planned tasks** run an instruction at a time, on a schedule or on an event, with the permissions of the
  person who created the task.
- **The watchdog** reacts to failed health checks and errors in the STEM log, with the permissions of the
  approver (by default the owner).
- **Background jobs**: memory extraction after each message, chat summaries, the social status every few days.

---

## 2. Trust boundaries

| Boundary | Trusted side | Untrusted / semi-trusted side |
|---|---|---|
| Chat platforms | Platform identity (Discord user id, WhatsApp JID) as delivered by Discord / Evolution API | Message content, file content, display names |
| Evolution API / RabbitMQ | Messages on the RabbitMQ queues are taken as genuine WhatsApp messages | Anyone who can publish to the queues |
| OpenAI | Tool calls are executed as requested by the model, within the permissions of the current person | Model output (can be influenced by prompt injection) |
| STEM | STEM API, database, MQTT broker, cloud | Data coming from devices, MQTT payloads, log lines, health check texts |
| Shell / SSH | Commands run as the operating system user of STEM, SSH with its keys | Every host reachable with these keys |

---

## 3. Identities and access

### 3.1 User profiles

Every person is a profile in `plugin_mirra_agent_user` with one or more channels (`plugin_mirra_agent_user_channel`).
A sender is identified only by the platform address: the Discord user id or the WhatsApp JID. Display names are
informational and never used for authorization.

| Type | How it is created | Permissions |
|---|---|---|
| Owner | Permission `OWNER`, assigned **only in the database** | Everything |
| Registered user | Created by an administrator, or a guest that was registered | As granted |
| Guest (approved) | Unknown sender, approved by the approver | None; chat only, no tools |
| Guest (pending) | Unknown sender, waiting for approval | Not answered by the AI at all |
| Guest (denied) | Rejected by the approver | Ignored |
| Internal | STEM itself (AI text engine) | None |

### 3.2 Approval of new contacts

With `users.requireApproval` (default `true`) an unknown sender gets a fixed "request pending" text. No AI request is
made for the sender. The approver gets the platform address (Discord name and id, WhatsApp number and name) and the
first message and decides. Only approved contacts reach the model.

### 3.3 Permissions

Higher permissions include lower ones. Every registered user can chat, send files and voice messages, read and edit
their own profile and plan tasks without any permission.

| Permission | Allows | Includes |
|---|---|---|
| `ASSISTANT` | Weather, fuel prices | |
| `IMAGES` | Image generation | |
| `REMINDERS` | Reminders | |
| `MEMORY` | Explicit remember / recall / forget, household facts visible to everybody | |
| `HOME_VIEW` | Read devices, lights, sensors, smart home profile | |
| `HOME_LIGHTS` | Switch and dim lights | `HOME_VIEW` |
| `HOME_CONTROL` | Control all smart home devices, change the smart home profile | `HOME_LIGHTS`, `HOME_VIEW` |
| `STEM_VIEW` | Read STEM: plugins, health checks, information blocks, notifications, events, MQTT, data store, log, user list | |
| `ADMIN` | Everything except `SHELL` and `OWNER` | all but `SHELL`, `OWNER` |
| `SHELL` | Shell commands on the STEM server and via SSH | |
| `OWNER` | Everything, also permissions added later | all |

Protections in the code:

- `OWNER` cannot be granted or revoked by Mirra; profiles of owners can only be changed by owners.
- `SHELL` can only be granted or revoked by someone who has `SHELL`.
- Nobody can remove their own `ADMIN`.
- Deleting profiles, assigning or removing channels and merging profiles are **owner actions**: the tool only
  registers them; they run after an owner confirms with a new message. Confirmation is refused in the requesting
  turn and in automatic turns (tasks, watchdog), so the model cannot confirm on its own. Pending actions expire
  after 24 hours.

### 3.4 Tools and the permission they need

| Area | Tool | Permission |
|---|---|---|
| STEM read | `stem_status` | registered user |
| | `stem_list_plugins`, `stem_health_checks`, `stem_information_blocks`, `stem_notifications`, `stem_list_commands`, `stem_data_get`, `stem_mqtt_read`, `stem_read_log`, `list_event_types`, `list_users` | `STEM_VIEW` |
| STEM control | `stem_run_command`, `stem_run_script`, `stem_run_health_checks`, `stem_mqtt_publish`, `stem_push_notification`, `stem_show_information`, `stem_remove_information` | `ADMIN` |
| | `stem_data_set`, `stem_sql`, `stem_cloud_share`, `stem_cloud_upload` | `ADMIN` (server files: `SHELL`) |
| | `watchdog_status` | `ADMIN` |
| Shell | `shell_run` (local or SSH) | `SHELL` and `agent.shell.enabled` |
| Smart home | `home_list_devices`, `home_get_device`, `home_get_lights`, `home_get_profile` | `HOME_VIEW` |
| | `home_control_device` | `HOME_CONTROL` (lights: `HOME_LIGHTS`) |
| | `home_set_profile` | `HOME_CONTROL` |
| Assistant | `get_weather`, `get_fuel_prices` | `ASSISTANT` |
| | `create_reminder`, `list_reminders`, `cancel_reminder` | `REMINDERS` |
| | `remember`, `recall`, `forget` | `MEMORY` |
| | `create_image` | `IMAGES` |
| | `set_mirra_status` | registered user |
| Messaging | `send_file`, `send_voice_message` (to the current chat) | registered user (server files: `SHELL`) |
| | `send_message`, and `send_file` / `send_voice_message` to other users | `ADMIN` |
| Tasks | `create_task`, `list_tasks`, `cancel_task` | registered user (others' tasks: `ADMIN`) |
| Users | `get_user_profile`, `update_user_profile` (own) | registered user |
| | same for other users | `STEM_VIEW` / `ADMIN` |
| | `list_access_requests`, `decide_access_request`, `create_user`, `set_user_registered`, `list_permissions`, `set_user_permissions` | `ADMIN` |
| | `link_user_channel`, `unlink_user_channel`, `merge_users`, `delete_user` | request: `ADMIN`, run: owner confirmation |
| | `confirm_owner_action`, `list_owner_actions` | `OWNER` |
| Web | OpenAI web search (if `agent.webSearch`) | registered user |

---

## 4. Capabilities with high impact

| Capability | What Mirra can do | Limits in the code |
|---|---|---|
| Console commands | Any STEM console command with output | `agent.blockedCommands` (default `stop`, `reboot`), timeout |
| Scripts | Any script of the STEM script engine | Timeout |
| Shell / SSH | Any command as the STEM OS user, locally and on every host the STEM SSH keys reach | `SHELL` permission, `BatchMode` (no passwords), host/user validation, optional `agent.shell.allowedHosts`, timeout, output limit, every command logged |
| Database | Any SQL statement on the STEM database, including Mirra's own tables | `ADMIN`, 30 s query timeout, 200 rows, every statement logged |
| MQTT | Publish to any topic (devices, bridges) | `ADMIN` |
| Smart home | Switch every device, change thermostats, change the smart home profile | `HOME_CONTROL` |
| Messaging | Send texts, files and voice messages to every registered user | `ADMIN`; messages are added to the receiver's chat history |
| Files | Read server files and send them; upload to the cloud with a public link | `SHELL` for server files |
| Autonomy | Watchdog fixes problems on its own (mode `fix`) | Prompt rules (no deletion, no shutdown, no config or permission changes, no contact with others), cooldown 6 h per problem, 12 runs per day, paused without OpenAI credits |
| Tasks | Run any instruction later with the creator's permissions | Stored in the database, visible with `list_tasks` |

---

## 5. Data processing

| Data | Stored where | Sent to |
|---|---|---|
| Chat messages and answers | `plugin_mirra_memory` (per channel) | OpenAI (recent history with every request) |
| Chat summaries | `plugin_mirra_agent_summary` | OpenAI |
| Long term memories and embeddings | `plugin_mirra_agent_memory` | OpenAI (relevant memories in every request, texts for embeddings) |
| Profiles (name, birthday, location, e-mail, phone, ...) | `plugin_mirra_agent_user*` | OpenAI (profile of the current person in every request) |
| Files, images, voice messages | Not stored; recent files kept in memory for 30 minutes | OpenAI (content, transcription) |
| Tool results (device states, logs, SQL results, shell output, MQTT payloads) | Not stored by Mirra | OpenAI |
| Generated images | STEM cloud `/GeneratedImages/` with a public share link | Chat |
| Event catalog, social status | STEM data store | – |

OpenAI keeps chat requests for up to 30 days (default of the Responses API, needed for `previous_response_id` in
the tool loop). Memory extraction, summaries and one-shot texts are sent with `store=false`.

Secrets (OpenAI key, Discord token, Evolution API key, RabbitMQ password) are stored in plain text in
`plugins/mirra/config.yml`.

---

## 6. Risk analysis

Likelihood and impact: **L** low, **M** medium, **H** high. Residual risk after the existing mitigations.

| # | Threat | Likelihood | Impact | Existing mitigations | Residual |
|---|---|---|---|---|---|
| R1 | **Prompt injection through data**: text in files, web pages, log lines, health check messages, MQTT payloads, device names or event data makes the model call tools the person did not ask for | M | H | Tools limited to the permissions of the current person; owner actions need an owner message; system rules ask for confirmation of critical actions | **High** for owners and the watchdog (shell, SQL), medium for others |
| R2 | **Watchdog acts on injected log content** with the approver's permissions (incl. `SHELL` for an owner) | M | H | New-problem detection, cooldown, daily limit, rules forbid destructive actions, report to the approver | **High** while the approver has `SHELL` and `autonomy.mode: fix` |
| R3 | **Privilege escalation of `ADMIN` to `OWNER` or `SHELL` via `stem_sql`** (e.g. inserting a permission row) or console commands | M | H | Statements are logged | **High**: `ADMIN` must be treated as nearly equal to `OWNER` |
| R4 | **Server-side request forgery** with `send_file` `url`: any registered user can let Mirra fetch an internal URL (router, NAS, admin interfaces) and send the response to themselves | M | M | Only http/https; registered users only | **Medium** |
| R5 | **Forged WhatsApp messages** by anyone who can publish to the Evolution API RabbitMQ queues or call its webhook as the owner's JID | L | H | Credentials of RabbitMQ / Evolution API | Depends on network exposure of RabbitMQ and Evolution API |
| R6 | **Compromise of a Discord or WhatsApp account** of an owner or admin | L | H | Identity bound to platform ids | **High impact** if it happens; no second factor |
| R7 | **WhatsApp group chats**: a group is treated as one chat; once approved, every member writes with that profile | L | M | Approval of unknown chats; groups get no permissions by default | Low–medium; groups are not filtered explicitly |
| R8 | **Harmful shell command by the model** (wrong host, destructive command) | M | H | `SHELL` separate from `ADMIN`, rules to ask first, timeout, log, optional host allowlist (empty = all hosts) | **Medium–high** |
| R9 | **Unwanted smart home actions** (heating, sockets with devices attached) from misunderstood requests | M | M | Permission levels, rules to ask before actions outside the request | Medium |
| R10 | **Memory poisoning**: a user stores false household facts or profile data that influence later answers | M | L | Household facts need `MEMORY`; profiles editable only by their owner or `ADMIN` | Low |
| R11 | **Data disclosure to OpenAI**: personal data, logs, SQL results, shell output and files leave the house | H | M | Only the data needed for the current request; `store=false` for background jobs | Accepted by design; 30-day retention at OpenAI |
| R12 | **Disclosure to other users**: model reveals household data to a guest or a user without permissions | L | M | Guests have no tools; tools filtered per person; profile of other people only with `STEM_VIEW` | Low |
| R13 | **Public cloud links** of generated images and uploads are reachable by anyone with the link | M | L | Random file names | Low |
| R14 | **Cost / denial of service**: a registered user floods Mirra; long tool loops | M | L | Approval of new contacts; tool round limit; pause on empty credits | Low–medium; no per-user rate limit |
| R15 | **Secrets in plain text** in `config.yml` | L | H | File system permissions of the STEM host | Depends on host hardening |
| R16 | **Owner action confirmed by mistake** (ambiguous "ja", or injected content in the owner's next message) | L | H | New message required, not in automatic turns, description shown before, actions expire | Low |
| R17 | **Planned tasks keep running with old permissions** after a person loses them | L | M | Task runs with the current permissions of the owner at run time; deleted users' tasks are deactivated | Low |
| R18 | **Unavailability**: OpenAI credits or key fail, Mirra cannot help | M | L | Pause with fixed answer, alert to the approver, automatic resume | Low |

### Highest risks

1. **R1/R2 – prompt injection, especially through the watchdog.** Log lines and health check texts can contain
   text controlled by devices or external systems. The watchdog runs with the approver's permissions; for an owner
   this includes the shell.
2. **R3 – `ADMIN` can reach `OWNER` and `SHELL` through `stem_sql`** (and possibly console commands). The owner-only
   protections are only effective against administrators if `ADMIN` cannot write Mirra's tables.
3. **R4 – SSRF** through `send_file` with a URL for every registered user.
4. **R8 – shell commands chosen by the model** without a command allowlist.

---

## 7. Recommended hardening

Ordered by benefit:

1. **Limit the watchdog.** Use `autonomy.mode: report`, or set `users.approver` to a profile without `SHELL`, so
   automatic runs cannot use the shell. Add noisy or externally controlled log sources to `autonomy.ignore`.
2. **Give `ADMIN` only to people you would also give `OWNER`.** Alternatively restrict `stem_sql` and
   `stem_data_set` to `OWNER` (code change).
3. **Restrict `send_file` URLs** to `ADMIN`, or block private and local address ranges (code change).
4. **Set `agent.shell.allowedHosts`** to the hosts Mirra really needs, and give the STEM SSH key only the access it
   needs on those hosts (dedicated user, `sudo` rules for specific commands instead of `root`).
5. **Keep `SHELL` and `OWNER` to one person**, and keep `agent.blockedCommands` up to date with destructive STEM
   console commands.
6. **Protect the WhatsApp ingress**: RabbitMQ and the Evolution API only on the internal network, strong
   credentials, no public management ports.
7. **Secure the accounts** of owners and admins on Discord and WhatsApp with two-factor authentication and
   WhatsApp registration lock.
8. **Ignore WhatsApp group chats** explicitly if they are not needed (code change: skip `@g.us`).
9. **Protect `config.yml`**: readable only by the STEM user; rotate the OpenAI key if it may have leaked.
10. **Review regularly**: `list_users` / `list_permissions` with holders, `list_tasks` for all users,
    the log lines `Shell by`, `SQL by`, `changed permissions of`, `Owner action`.

---

## 8. Monitoring and audit trail

Mirra writes these events to the STEM log (plugin `mirra`):

| Event | Log text |
|---|---|
| Tool call | `Tool <name> for <person> {arguments}` |
| Denied tool | `Tool <name> denied for <person>` |
| Shell command | `Shell by <person> [on <host>]: <command>` |
| SQL statement | `SQL by <person>: <statement>` |
| Permission change | `<admin> changed permissions of <user>: +[...] -[...]` |
| Owner action | `Owner action #n requested by ...` / `confirmed by ...` / `rejected by ...` |
| Access decision | `<approver> approved / denied <guest>` |
| New guest | `New guest profile '<name>' for <channel>` |
| Watchdog | `Watchdog: handling n problem(s)` |
| Task | `Task #n for <owner> ...`, `Running task #n ...` |
| OpenAI pause | Message to the approver and STEM notification |

The chat history in `plugin_mirra_memory` shows what was asked and answered, including messages Mirra sent on
behalf of others.

---

## 9. Incident response

| Situation | Immediate steps |
|---|---|
| Unexpected actions by Mirra | Set `autonomy.enabled: false` and `agent.shell.enabled: false`, restart STEM; check the log for `Tool`, `Shell by`, `SQL by` |
| Compromised chat account | Remove the channel or permissions in `plugin_mirra_agent_user_channel` / `_permission` (database) and restart STEM |
| Leaked OpenAI key | Revoke the key in the OpenAI dashboard, set a new `openAI.token` |
| Leaked Discord token / Evolution API key | Regenerate in the Discord developer portal / Evolution API, update `config.yml` |
| Unwanted planned task | `cancel_task`, or `UPDATE plugin_mirra_agent_task SET active = 0 WHERE id = ...` |
| Stop Mirra completely | Disable the plugin or remove `mirra.jar` and restart STEM |
