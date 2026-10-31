![MirraNET Logo](https://raw.githubusercontent.com/LinzN/Stem/master/stem-core/src/main/resources/header-logo.png)

# mirra

> **M**ultifunctional **I**ntelligent **R**eal-time **R**esponsive **A**ssistant — the AI assistant of [STEM](https://github.com/LinzN/Stem).

Mirra is an AI agent based on the OpenAI [Responses API](https://platform.openai.com/docs/api-reference/responses).
She talks to people on Discord and WhatsApp, controls STEM and its smart home, remembers what people tell her
across chats and turns STEM system events into readable messages.

See [docs/security/SECURITY.md](docs/security/SECURITY.md) for permissions, data flows and the risk analysis.

---

## Features

- **Chat** on Discord (private messages) and WhatsApp private chats (via [Evolution API](https://github.com/LinzN/evolution-api-java)); WhatsApp groups and status updates are ignored
- **Agent loop**: the model can call several tools in a row (up to `agent.maxToolRounds`) before it answers
- **STEM control**: status, plugins, health checks, information blocks, notifications, console commands,
  scripts, MQTT, devices and smart home profile, gated by permissions
- **User profiles**: one profile per person with any number of channels (Discord, WhatsApp), permissions,
  profile fields and attributes Mirra fills in herself; unknown senders get a guest profile
  (see [Users](#users)); tools a user may not use are not offered to the model at all
- **Files**: images, PDFs, Office documents, text and code files and voice messages sent on Discord or
  WhatsApp (see [Files](#files))
- **Sending**: images, files Mirra writes herself, files of the server and voice messages into the chat;
  messages, files and voice messages to other registered users on request
- **Voice**: text to speech with an OpenAI Realtime voice (the text is read aloud word for word and checked
  against the transcript), optionally as answer to every voice message
- **Voice devices**: smart speakers in the house (e.g. Onju V3 boards in Google Home Minis with the
  [voice-satellite](https://github.com/LinzN/voice-satellite) plugin) are a chat channel of their own: tap, speak,
  Mirra answers out loud; announcements on one or all speakers (see [Voice devices](#voice-devices))
- **Tasks**: Mirra plans tasks for later, on a schedule or on events (e.g. "switch the 3D printer off when the
  print is finished") and carries them out herself (see [Tasks](#tasks))
- **Watchdog**: Mirra notices failed health checks and errors in the STEM log, analyzes them, fixes what is
  safe and reports to the approver (see [Watchdog](#watchdog))
- **Out of credits**: when the OpenAI credits are used up, Mirra pauses and only sends a fixed answer
  (see [OpenAI credits](#openai-credits))
- **Shell**: commands on the STEM server and via SSH on registered servers, with Mirra's own key per server and a
  level (read / ops / full) enforced by the server (separate `SHELL` permission)
- **Memory** in three layers (see [Memory](#memory))
- **Reminders**: due reminders are sent to the user on Discord or WhatsApp
- **Notifications**: information blocks with the intent `NOTIFY_USER` are sent to the default Discord user
  and WhatsApp chat
- **AI text engine**: STEM can let Mirra phrase event texts
- **Social status**: Mirra chooses a new Discord/WhatsApp status at a random time every few days (about once a
  week), each time with a random theme
- **Web search** (optional): the OpenAI web search tool for registered users

---

## Architecture

```
Discord / WhatsApp ──► ChatContext (identity, permissions)
                          │
                          ▼
                     MirraAgent ──► OpenAIClient ──► POST /v1/responses
                      │   │                              │
                      │   └──── ToolRegistry ◄── function_call ──┘
                      │           ├─ StemTools      (STEM API)
                      │           ├─ HomeTools      (home-devices)
                      │           └─ AssistantTools (weather, fuel, reminders, memory, images)
                      ▼
                 MemoryKeeper (background) ──► LongTermMemory + chat summaries
```

Mirra uses her own small HTTP client for the OpenAI API (`agent/openai`); there is no OpenAI SDK dependency.
Every request sends the instructions (personality, situation, relevant memories, chat summary, rules), the
recent chat history and the tool definitions in strict JSON schema mode. Tool results are sent back with
`previous_response_id` until the model answers with text. When the round limit is reached, Mirra asks the
model for a final answer without tools.

---

## Tools

| Tool                      | Permission                                   | Description                                         |
|---------------------------|----------------------------------------------|-----------------------------------------------------|
| `stem_status`             | registered users                             | Version, uptime, memory, threads of STEM            |
| `stem_list_plugins`       | `STEM_VIEW`                                  | Loaded plugins                                      |
| `stem_health_checks`      | `STEM_VIEW`                                  | Results of the health checks                        |
| `stem_information_blocks` | `STEM_VIEW`                                  | Active information blocks                           |
| `stem_notifications`      | `STEM_VIEW`                                  | Recent notifications                                |
| `stem_list_commands`      | `STEM_VIEW`                                  | Registered console commands                         |
| `stem_run_health_checks`  | `ADMIN`                                      | Runs all health checks now                          |
| `stem_run_command`        | `ADMIN`                                      | Runs a console command and returns its output       |
| `stem_run_script`         | `ADMIN`                                      | Runs a script of the STEM script engine             |
| `stem_mqtt_publish`       | `ADMIN`                                      | Publishes an MQTT message                           |
| `stem_push_notification`  | `ADMIN`                                      | Sends a STEM notification                           |
| `stem_show_information`   | `ADMIN`                                      | Shows an information block                          |
| `stem_remove_information` | `ADMIN`                                      | Removes (expires) an active information block       |
| `stem_cloud_share`        | `ADMIN`                                      | Public link for a file in the STEM cloud            |
| `stem_cloud_upload`       | `ADMIN`                                      | Stores a text file (or a server file with `SHELL`) in the cloud |
| `stem_data_get`           | `STEM_VIEW`                                  | Reads a JSON container of the STEM data store       |
| `stem_data_set`           | `OWNER`                                      | Writes a JSON container of the STEM data store      |
| `stem_sql`                | `OWNER`                                      | Runs an SQL statement on the STEM database (max. 200 rows) |
| `stem_mqtt_read`          | `STEM_VIEW`                                  | Listens to MQTT topics for a few seconds            |
| `list_users`              | `STEM_VIEW`                                  | User profiles and their channels (guests optional)  |
| `get_user_profile`        | own profile; others `STEM_VIEW`              | Profile fields, attributes, channels, permissions   |
| `update_user_profile`     | own profile; others `ADMIN`                  | Sets or deletes a profile field or attribute        |
| `list_access_requests`    | `ADMIN`                                      | People waiting for approval                         |
| `decide_access_request`   | `ADMIN`                                      | Approves or denies (blocks) a person                |
| `create_user`             | `ADMIN`                                      | Creates a registered user                           |
| `link_user_channel`       | request: `ADMIN`, confirm: owner             | Assigns a channel (or the channels of a guest profile) to a user |
| `unlink_user_channel`     | request: `ADMIN`, confirm: owner             | Removes a channel from a user                       |
| `list_permissions`        | `ADMIN`                                      | All permissions with meaning, unlocked tools and holders |
| `set_user_permissions`    | `ADMIN`      (`SHELL` only by users with `SHELL`) | Grants and revokes several permissions, optionally tells the user |
| `set_user_registered`     | `ADMIN`                                      | Turns a guest into a registered user or back        |
| `merge_users`             | request: `ADMIN`, confirm: owner             | Merges two profiles of the same person              |
| `delete_user`             | request: `ADMIN`, confirm: owner             | Deletes a profile with channels, memories, reminders, tasks and chat history |
| `confirm_owner_action`    | `OWNER`                                      | Carries out or rejects a confirmed owner action     |
| `list_owner_actions`      | `OWNER`                                      | Actions waiting for the owner                       |
| `create_task`             | registered users                             | Plans a task at a time, on a cron schedule or on an event |
| `list_event_types`        | `STEM_VIEW`                                  | STEM events seen so far with example data           |
| `list_tasks`              | registered users (all users: `ADMIN`)   | Planned tasks                                       |
| `cancel_task`             | owner or `ADMIN`                             | Cancels a planned task                              |
| `stem_read_log`           | `STEM_VIEW`                                  | Latest STEM log entries, filtered by level and text |
| `watchdog_status`         | `ADMIN`                                      | Watchdog status, check now                          |
| `shell_run`               | `SHELL`                                      | Shell command on the STEM server or on a registered SSH server |
| `ssh_servers`             | `SHELL`                                      | Registered SSH servers with level and key age       |
| `ssh_server_add`          | `SHELL` (owner confirms the host key)        | Generates a key for a new server, installs it       |
| `ssh_server_manage`       | `SHELL` (install/new host key: owner confirms) | Check, disable, rotate the key, remove, reinstall |
| `home_list_devices`       | `HOME_VIEW`                                  | Devices of home-devices with state                  |
| `home_get_device`         | `HOME_VIEW`                                  | Details of one device                               |
| `home_get_lights`         | `HOME_VIEW`                                  | State of all lights                                 |
| `home_control_device`     | `HOME_CONTROL` (lights also with `HOME_LIGHTS`) | Switches or dims a device                         |
| `home_get_profile`        | `HOME_VIEW`                                  | Active smart home profile                           |
| `home_set_profile`        | `HOME_CONTROL`                                 | Changes the smart home profile                      |
| `get_weather`             | `ASSISTANT`                                  | Weather of a location                               |
| `get_fuel_prices`         | `ASSISTANT`                                  | Fuel prices                                         |
| `create_reminder`, `list_reminders`, `cancel_reminder` | `REMINDERS`, registered users       | Reminders                    |
| `remember`, `forget`      | `MEMORY`                                     | Stores or deletes a long term memory                |
| `recall`                  | `MEMORY`                                             | Searches the long term memory              |
| `create_image`            | `IMAGES`                                     | Generates an image and sends it into the chat (link elsewhere); a copy is stored in the cloud under `/GeneratedImages/` |
| `send_message`            | `ADMIN`                                      | Text message to another registered user             |
| `send_file`               | registered users (others: `ADMIN`, server files: `SHELL`) | Sends a file from text, a server path or a URL |
| `send_voice_message`      | registered users (others: `ADMIN`)      | Speaks a text and sends it as voice message         |
| `list_voice_devices`      | `HOME_VIEW`                             | Voice devices with room and online state            |
| `announce`                | `HOME_CONTROL`                          | Says a text on one or all voice devices             |
| `set_mirra_status`        | no guests                                    | Sets the Discord activity and a WhatsApp status     |

Higher permissions include lower ones (see [Permissions](#permissions-in-the-chat)). `SHELL` is separate and not
part of `ADMIN`; give it only to people who may run any command on the server. SSH only goes to servers registered in
Mirra's own key store (see [SSH servers](#ssh-servers)). Guests have no permissions and no tools. Console commands listed in `agent.blockedCommands` are
never run. Messages Mirra sends to other users are added to their chat history, so she knows the context
when they answer.

---

## Files

Files sent on Discord (private messages) or WhatsApp are read together with the message:

| Type                                                         | How Mirra reads it                                          |
|--------------------------------------------------------------|-------------------------------------------------------------|
| Images (JPEG, PNG, WebP, GIF, WhatsApp stickers)             | Sent to the model as image                                  |
| PDF, Word, Excel, PowerPoint, OpenDocument, RTF              | Sent to the model as file; OpenAI extracts text and pages   |
| Text, code, CSV, JSON, YAML, logs and other UTF-8 text files | Included as text (up to 60,000 characters)                  |
| Voice messages and audio files                               | Transcribed with `model.<name>.transcriptionModel`          |
| Videos and other binary files                                | Not read; Mirra says so                                     |

Files from the last `attachments.keepMinutes` minutes (at most five messages) are sent again with the next
messages of the same chat, so follow-up questions work. The chat history only stores a short note per file and
the transcripts of voice messages, never the file content. Guests cannot send files unless
`attachments.guests` is enabled.

WhatsApp media is loaded through the Evolution API endpoint `getBase64FromMediaMessage`, or taken from the
webhook directly if "Webhook base64" is enabled for the instance.

---

## Users

Every person is one profile (`plugin_mirra_agent_user`) with:

- **channels**: any number of Discord accounts and WhatsApp numbers; Mirra recognizes the person on all of them,
  long term memories, reminders and tasks belong to the person, the chat history is kept per channel
- **permissions** (see [Permissions](#permissions-in-the-chat)); guests have none
- **profile fields**: `full_name`, `nickname`, `birthday`, `language`, `timezone`, `location`,
  `preferred_channel` (used when Mirra sends messages to the person), `notes`
- **attributes**: any further data as key and value, e.g. `email`, `phone`, `address`, `employer`, `pets`

Mirra fills the profile herself: after every answer the memory model looks for lasting personal data the person
stated (name, birthday, city, email, ...) and stores it; people can also ask her to change or delete entries.
The profile of the person is part of every request, so Mirra knows it without asking.

### Approval of new contacts

When somebody unknown writes to Mirra (`users.requireApproval`, on by default):

1. Mirra creates a guest profile with the access state `pending` and answers with a short "request pending"
   message (`users.pendingMessage`).
2. The approver (`users.approver`, by default `default.identityName`) gets a message on their preferred channel:
   on Discord with the Discord name and id, on WhatsApp with the phone number and the WhatsApp name, plus the
   first message.
3. The approver answers Mirra in the chat ("ja, Max darf schreiben" / "nein"), Mirra calls
   `decide_access_request`. Approved people get `users.approvedMessage` and an answer to their first message;
   denied people get `users.deniedMessage` (empty by default, so nothing) and are ignored from then on.

Until the decision, further messages are not answered; the pending info is repeated at most every six hours.
`list_access_requests` shows the open requests; `decide_access_request` can also block or unblock somebody
later. An approved guest can chat but has no permissions; register the person to grant permissions.

### Permissions in the chat

Administrators (`ADMIN`) manage permissions in plain language, e.g. "Dennis darf Lichter schalten",
"Anna darf ab jetzt Erinnerungen anlegen" or "Dennis darf keine Lichter mehr schalten". Mirra maps the request
to permissions with `list_permissions` (every permission has a description and the list of tools it unlocks,
computed from the registered tools) and changes them with `set_user_permissions`; changes apply immediately and
the user can be told about them. Granting a permission to a guest registers the guest. Nobody can remove their
own `ADMIN`, `SHELL` can only be granted or revoked by users who have it, `OWNER` is only assigned in the
database and profiles of owners can only be changed by owners.

Every registered user can chat, send files and voice messages, keep a profile and plan tasks without any
permission. Higher permissions include lower ones:

| Permission     | Allows                                                                              | Includes |
|----------------|-------------------------------------------------------------------------------------|----------|
| `ASSISTANT`    | Weather and fuel prices                                                             |          |
| `IMAGES`       | Create images                                                                       |          |
| `REMINDERS`    | Create, list and cancel reminders                                                   |          |
| `MEMORY`       | Remember, recall and forget explicitly, household facts                             |          |
| `HOME_VIEW`    | See devices, lights, sensors and the smart home profile                             |          |
| `HOME_LIGHTS`  | Switch and dim lights                                                               | `HOME_VIEW` |
| `HOME_CONTROL` | Control all smart home devices and the smart home profile                           | `HOME_LIGHTS`, `HOME_VIEW` |
| `STEM_VIEW`    | See plugins, health checks, information blocks, notifications, events, MQTT, users  |          |
| `ADMIN`        | Everything except `SHELL`: commands, scripts, MQTT, database, cloud, messages to others, users and permissions | all except `SHELL`, `OWNER` |
| `SHELL`        | Shell commands on the STEM server and via SSH                                       |          |
| `OWNER`        | Everything, also permissions added later; only assigned in the database             | all      |

Permissions of earlier versions are renamed automatically on start: `STATUS_LIGHT` → `HOME_VIEW`,
`SWITCH_LIGHT` → `HOME_LIGHTS`, `GET_WEATHER`/`GET_FUEL_PRICE` → `ASSISTANT`, `CREATE_IMAGE` → `IMAGES`,
`CREATE_REMINDER` → `REMINDERS`, `LONG_TERM_MEMORY_ACCESS`/`_WRITE` → `MEMORY`, `STEM_READ` → `STEM_VIEW` +
`HOME_VIEW`, `STEM_ADMIN` → `ADMIN`; `STATUS_STEM` is dropped (every registered user may ask for the status).

Unknown senders get a guest profile named after their chat name (`users.autoCreateGuests`). An administrator can
ask Mirra to register a guest or grant permissions.

### Changes only the owner decides

Deleting a profile, assigning a channel to an existing user ("der Discord-Account von gamer42 gehört Dennis",
"die WhatsApp-Nummer 49176... gehört zu Anna"), removing a channel and merging profiles always need the
confirmation of an owner (`OWNER`):

1. An administrator or the owner asks Mirra. The tool only registers the change as pending action; nothing
   happens yet.
2. If the owner asked, Mirra asks back in the same chat what exactly will happen. If an administrator asked, the
   owners get a message "🔐 Bestätigung nötig (Aktion #n) ...".
3. Only when the owner answers "ja" in a new message, Mirra calls `confirm_owner_action` and the change runs.

The confirmation is refused in the same turn in which the change was requested and in automatic turns (tasks,
watchdog), so Mirra cannot confirm on her own. Pending actions expire after 24 hours. Owners cannot be deleted.
Deleting a profile removes its channels, permissions, profile data, long term memories, reminders, planned tasks
and the chat history of its channels.

### Migration from earlier versions

On the first start the old identity tables are copied into the new tables in one transaction
(only while there are no profiles yet, `users.migrated` is set afterwards):

| Old                                      | New                                   |
|------------------------------------------|---------------------------------------|
| `plugin_mirra_identity_user`             | `plugin_mirra_agent_user`             |
| `plugin_mirra_identity_user_tokens`      | `plugin_mirra_agent_user_channel`     |
| `plugin_mirra_identity_user_permissions` | `plugin_mirra_agent_user_permission`  |
| `plugin_mirra_reminder` (active ones)    | `plugin_mirra_agent_reminder`         |

Long term memory owners `identity:<name>` and `token:<channel>:<address>` become `user:<id>`; guests with
memories get a guest profile. Duplicate names get a suffix, unknown permissions and channels are skipped and
logged, old permission names are mapped to the new ones. The old tables are not changed and are no longer used;
delete them once everything works.

---

## Voice devices

A voice device (smart speaker) is a chat channel of the source `VOICE`, its address is the device id
(e.g. `mirra-voice-a1b2c3`). Mirra itself only offers the speech interface `MirraVoice`; the devices are driven
by a separate plugin such as [voice-satellite](https://github.com/LinzN/voice-satellite), which registers a
speaker and hands recorded speech to Mirra.

- **A new device is a guest.** The first time somebody speaks on it, the approver gets the usual access request
  ("Sprachgerät: Küche (mirra-voice-a1b2c3)") and the device says the pending message. Until then it answers
  nothing.
- **Whoever stands next to the speaker talks with the permissions of its profile.** Do not link a speaker to
  the owner's profile. Create a profile for the house or the room instead ("Mirra, leg ein Profil Küche an und
  ordne ihm das Sprachgerät mirra-voice-a1b2c3 zu") and give it only what a shared device should do, e.g.
  `ASSISTANT`, `REMINDERS`, `HOME_LIGHTS`.
- **Answers are spoken**: Mirra gets a rule for short, natural spoken answers without emojis, lists or links.
  If the answer ends with a question, the device listens again right away.
- **Speaking on a device**: `announce` ("Sag in der Küche, dass das Essen fertig ist"), planned tasks
  ("um 7 Uhr im Schlafzimmer: Guten Morgen und das Wetter"), reminders and messages to a profile whose only or
  preferred channel is a speaker. A speaker is never chosen as the default channel of a person otherwise.
- **Speech to text** uses `openAI.transcriptionModel`, **text to speech** the `voice.*` settings.
- **Door bell**: when a door bell of home-devices rings (`MQTTDoorRingEvent`), every online device (or those in
  `voice.doorbell.devices`) plays a door bell sound right away, also in the middle of a conversation, and then
  says `voice.doorbell.text` if set. The sound comes from the device plugin (voice-satellite: built-in "ding-dong"
  or an own `sounds/doorbell.wav`).

---

## Tasks

A task is an instruction in natural language plus a trigger. When it triggers, Mirra gets the instruction as if
its owner had just written it, with the owner's permissions, carries it out with all her tools and sends the result
to the owner. Anything Mirra can do in a chat can be planned. Examples:

- "Schalte heute um 22 Uhr den 3D-Drucker aus" (time)
- "Jeden Morgen um 7 Uhr: schick mir das Wetter und die Termine" (cron)
- "Prüfe jede Stunde die Health-Checks und melde dich nur, wenn etwas fehlschlägt" (cron, silent if all is fine)
- "Wenn die Außentemperatur unter 3 Grad fällt, schick mir eine Glätte-Warnung" (event with condition)
- "Wenn ein Druck fertig ist, schalte den Drucker aus" (event)
- "Wenn ein Backup fehlschlägt, starte es neu und sag mir Bescheid" (information block event)

| Trigger | Parameters                         | Notes                                                    |
|---------|------------------------------------|----------------------------------------------------------|
| `time`  | `run_at` (local time)              | Once                                                     |
| `cron`  | `cron` (5 fields)                  | Repeating                                                |
| `event` | `event`, filters, `repeat`         | Once by default, every time with `repeat`                |

| Event                                              | Source                                   | Filter                         |
|----------------------------------------------------|------------------------------------------|--------------------------------|
| `stem_event`                                       | Any event of any STEM plugin, by `event_name` | `device`/`match_value` in the event data, `condition` |
| `mqtt`                                             | Any MQTT topic                           | `topic`, `match_value` in the payload, `condition` |
| `information`                                      | Any new STEM information block           | `match_value` in title or text |
| `print_finished`, `print_failed`, `print_cancelled` | 3D printers of home-devices             | `device` (part of the name)    |
| `device_on`, `device_off`                          | Switches of home-devices (real changes only) | `device`                    |
| `door_ring`                                        | Door bells of home-devices               | `device`                       |

`stem_event` uses STEM's catch-all event delivery (STEM `b585c21` or newer): Mirra sees every event of every
plugin, turns it into JSON (record components or getters, one level of nested objects) and keeps a catalog of
the events seen with an example, which she reads with `list_event_types` to find the right event and fields.
The catalog is stored in the STEM data store under `mirra-event-catalog`.

A condition compares a field of the event data or JSON payload (dotted path, e.g.
`environmentSensor.temperature`) with a value (`<`, `<=`, `>`, `>=`, `==`, `!=`, `contains`; numbers are compared
as numbers). Tasks with a condition trigger when the condition becomes true, not on every event while it stays
true. If a task should only report in some cases and none applies, Mirra answers `NO_REPORT` and nothing is sent.

Tasks are stored in `plugin_mirra_agent_task` and armed again after a restart. Time tasks that were due while
STEM was offline run one minute after the start if they are at most one hour late; older ones are skipped and the
owner gets a message. For `mqtt` tasks, messages in the first seconds after subscribing (retained values) are
ignored.

---

## SSH servers

Mirra logs in to other machines only with her **own key store**, not with the SSH keys of the user STEM runs as.
Every server gets its own ed25519 key pair, generated by Mirra, and its own pinned host key. The store is in the
plugin folder (directories `700`, files `600`):

```
plugins/mirra/ssh/servers.json          registered servers (address, login user, level, host keys)
plugins/mirra/ssh/keys/<name>           private key of the server, <name>.pub public key
plugins/mirra/ssh/known_hosts/<name>    host keys, written when an owner confirmed the fingerprint
plugins/mirra/ssh/audit.log             one JSON line per command (time, person, server, command, exit code)
plugins/mirra/ssh/mirra-ssh-install.sh  installer for the server side
```

### Adding a server

> Mirra, richte SSH für den Server nas ein, 10.50.0.20, Stufe ops, Zugriff nur von 10.50.0.11, installiere als root.

1. `ssh_server_add` generates the key pair and reads the host keys of the server.
2. The owner gets the host key fingerprint and confirms it after comparing it on the server
   (`ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`). Until then the server cannot be used.
3. Installation on the server, either
   - automatically (`install_as`): Mirra logs in once with the STEM user's own SSH login as `root` (or a user with
     password-less sudo), checked against the confirmed host key, and runs the installer, or
   - by hand: Mirra sends `mirra-ssh-install.sh` and the command line to run on the server as root.

The installer sets up on the server:

| | |
|---|---|
| login user | `mirra` (or the chosen one), system account, member of `systemd-journal` and `adm` to read logs |
| `authorized_keys` | Mirra's key with `from="<allowed_from>",restrict,command="/usr/local/bin/mirra-gate"`; `~/.ssh` and the file belong to root, so the login cannot change its own restrictions; other keys in the file are kept |
| `/usr/local/bin/mirra-gate` | forced command: logs every command to syslog (`mirra-gate`), refuses interactive logins |
| `/etc/sudoers.d/mirra-<user>` | what the login may run as root, by level (checked with `visudo`) |
| `/usr/local/sbin/mirra-keys` | key rotation and removal from Mirra |

### Levels

The level is enforced by the server (sudoers), not by Mirra:

| Level | Mirra may |
|---|---|
| `read` | run commands as the unprivileged login, read the journal and `/var/log`, `sudo systemctl status/is-active/list-units`, `sudo docker ps/logs/stats --no-stream/inspect/images` |
| `ops` | also `sudo systemctl start/stop/restart/reload <unit>`, `sudo systemctl reboot`, `sudo docker start/stop/restart`, `sudo apt-get update`, `sudo apt-get -y upgrade/full-upgrade/autoremove` |
| `full` | `sudo` for everything (a `root` login is always `full`) |

Automatic runs (watchdog, planned tasks) may only use servers up to `agent.shell.automaticMaxLevel` (default
`read`). Changing the level reinstalls the server side (`ssh_server_manage` action `install` with `level`, the owner
confirms); `check` reads the level installed on the server.

### Keys

- **Rotate**: `ssh_server_manage` action `rotate` generates a new key pair, sends the new public key through the
  old login (the restrictions stay as installed) and tests the new one.
- **Revoke**: action `remove` deletes the key on the server and in Mirra; `disable` only stops using the server.
- **New host key** (server reinstalled): connections fail until action `host_key` read the new key and an owner
  confirmed it.

The private keys are not encrypted: Mirra has to use them without anybody typing a passphrase, and a passphrase
stored next to them would not protect them. The protection is the file permissions of the STEM host, `from=` (the
key is useless from other machines), the level on each server and one key per server.

---

## Watchdog

Every `autonomy.intervalMinutes` Mirra looks for new problems in STEM:

- health checks with an `ERROR` result
- `SEVERE`/`ERROR` entries in the STEM log since the last round (stack frames, frame lines, Mirra's own OpenAI
  errors and texts in `autonomy.ignore` are skipped)

If there are new problems, Mirra handles them in one turn on behalf of the approver, with the approver's
permissions: she analyzes them with her tools (health checks, log, devices, console commands, the shell if the
approver has `SHELL`). In mode `fix` she also fixes what is safe and reversible (run a check again, retry a job,
reconnect, restart a service) and verifies the result; she never deletes data, shuts down or reboots machines,
changes configuration or permissions, or contacts other people. In mode `report` she only analyzes. Then she sends
a short report to the approver, or nothing if everything is harmless.

The same problem (numbers and ids ignored) is handled at most once per `autonomy.cooldownMinutes`, and at most
`autonomy.maxRunsPerDay` turns run per day. Nothing runs while OpenAI reports that the credits are used up.

---

## OpenAI credits

When OpenAI answers that the credits are used up (`insufficient_quota`), Mirra pauses:

- no more requests are sent to OpenAI; chats are answered with the fixed text `openAI.pausedMessage`
  (approval requests of new contacts keep working, they need no AI)
- the approver gets a message and a STEM notification once
- every 15 minutes one request is let through as a test; when it works again, Mirra is active again and the
  approver is told
- the watchdog does not run while Mirra is paused

An invalid or revoked API key is reported to the approver as well (at most every six hours).

---

## Memory

| Layer            | Scope                       | Storage                          | How it works                                                     |
|------------------|-----------------------------|----------------------------------|------------------------------------------------------------------|
| Chat history     | one chat (user token)       | `plugin_mirra_memory`            | The last `agent.historyMessages` messages are sent with each request |
| Chat summary     | one chat                    | `plugin_mirra_agent_summary`     | When `memory.summaryThreshold` messages are not summarized yet, the older ones are merged into a running summary |
| Long term memory | one person or the household | `plugin_mirra_agent_memory`      | Facts, preferences, people, events and plans with importance 1–5 and an embedding |

After every answer a background job asks the memory model which lasting facts the exchange contains and
adds, updates or deletes long term memories (`memory.autoExtract`); lasting personal data also goes into the
user profile. Memories belong to the user profile (`user:<id>`), so what Niklas tells Mirra on WhatsApp is also
known on Discord.
Household facts are stored as `global` and are visible to everyone with `MEMORY`.

For each message Mirra adds the most important memories of the person and the memories most similar to the
message (cosine similarity of `text-embedding-3-small` vectors, keyword search as fallback) to the
instructions, up to `memory.contextSize`. Near duplicates (similarity ≥ 0.92) are merged instead of stored
twice.

On the first start the entries of the old `plugin_mirra_longterm_memory` table are imported once as global
facts (`memory.legacyImported` is set afterwards).

---

## Requirements

| Requirement | Version                                                                                       |
|-------------|-----------------------------------------------------------------------------------------------|
| STEM        | `master` from 69b2e69 (command execution, catch-all events, log access), database module configured |
| SSH         | `ssh`, `ssh-keygen`, `ssh-keyscan` (OpenSSH 7.2+) on the STEM host for SSH servers; `bash`, `sudo` on the servers |
| Plugins     | [home-devices](https://github.com/LinzN/home-devices), [weather](https://github.com/LinzN/weather), [gas-station](https://github.com/LinzN/gas-station) |
| OpenAI      | API key with access to the Responses, Embeddings, Images and Audio API (transcription, speech) |
| Discord     | Bot token with the **Message Content** intent (optional)                                      |
| WhatsApp    | Evolution API v2 instance with RabbitMQ (optional), [evolution-api-java](https://github.com/LinzN/evolution-api-java) 0.0.5 |

---

## Installation

1. Build the plugin with `mvn clean package` (or take the build from Jenkins).
2. Copy `target/mirra.jar` into the `plugins` folder of STEM and the libraries from
   `target/libraries` as `libraries.zip` (folder `libraries` inside) into `plugins/mirra/`.
3. Start STEM once to create `plugins/mirra/config.yml` and the database tables, fill in the config and
   restart STEM.
4. Make yourself owner in the database (see [Database](#database)); existing identities and permissions are
   migrated automatically (see [Users](#users)). Further permissions can be managed in the chat.

---

## Configuration (`config.yml`)

| Key                                | Default                     | Description                                  |
|------------------------------------|-----------------------------|----------------------------------------------|
| `openAI.token`                     | `xxxx`                      | OpenAI API key                               |
| `openAI.baseUrl`                   | `https://api.openai.com/v1` | API base URL (e.g. for a proxy)              |
| `openAI.pausedMessage`             | `Sorry, mein OpenAI-Guthaben ...` | Answer while Mirra is paused because the credits are used up |
| `openAI.timeoutSeconds`            | `120`                       | Timeout of one request                       |
| `default.model`                    | `mirra`                     | Name of the model configuration below        |
| `default.identityName`             | `Niklas`                    | Name used as sender for STEM events          |
| `model.<name>.textOpenAIModel`     | `gpt-4.1`                   | Chat model, e.g. `gpt-4.1`, `gpt-6-sol`      |
| `model.<name>.imageOpenAIModel`    | `gpt-image-1`               | Image model, e.g. `gpt-image-1`, `gpt-image-2` |
| `model.<name>.aiRoleDescription`   | `You are Mirra, ...`        | Personality of Mirra                         |
| `model.<name>.memoryModel`         | text model                  | Model for memory extraction and summaries    |
| `model.<name>.embeddingModel`      | `text-embedding-3-small`    | Embedding model of the long term memory      |
| `model.<name>.transcriptionModel`  | `gpt-4o-mini-transcribe`    | Speech to text model for voice messages      |
| `model.<name>.reasoningEffort`     | empty                       | Reasoning effort of reasoning models (e.g. `low`, `medium`, `high`), empty for the model default |
| `model.<name>.memoryReasoningEffort` | empty                     | Reasoning effort of the memory model, e.g. `none` to save tokens; empty for the model default |
| `agent.maxToolRounds`              | `10`                        | Tool rounds per message                      |
| `agent.historyMessages`            | `16`                        | Chat messages sent with each request         |
| `agent.webSearch`                  | `false`                     | Offer the OpenAI web search tool to registered users |
| `agent.blockedCommands`            | `[stop, reboot]`            | Console commands Mirra must not run          |
| `agent.commandTimeoutSeconds`      | `60`                        | Timeout of a console command                 |
| `agent.scriptTimeoutSeconds`       | `120`                       | Timeout of a script                          |
| `agent.socialStatus.enabled`       | `true`                      | Let Mirra choose a social status             |
| `agent.socialStatus.minDays`       | `4`                         | Fewest days until the next status            |
| `agent.socialStatus.maxDays`       | `10`                        | Most days until the next status              |
| `agent.socialStatus.earliestHour`  | `9`                         | Earliest hour of day for a new status        |
| `agent.socialStatus.latestHour`    | `21`                        | Latest hour of day for a new status          |
| `attachments.enabled`              | `true`                      | Read files sent to Mirra                     |
| `attachments.guests`               | `false`                     | Also read files of guests                    |
| `attachments.maxSizeMB`            | `25`                        | Largest file Mirra reads (at most 50)        |
| `attachments.keepMinutes`          | `30`                        | How long files are sent again for follow-up questions, `0` to disable |
| `voice.enabled`                    | `true`                      | Allow voice messages from Mirra              |
| `voice.replyToVoice`               | `false`                     | Answer voice messages with a voice message   |
| `voice.transcriptionLanguage`      | `de`                        | Language of voice messages and voice devices (better recognition) |
| `voice.transcriptionPrompt`        | `Jemand spricht mit Mirra, ...` | Context and words the speech may contain (names, rooms, devices) |
| `voice.engine`                     | `realtime`                  | `realtime`: a Realtime model reads the text aloud; `speech`: `/audio/speech` |
| `voice.realtimeModel`              | `gpt-realtime-2.1-mini`     | Realtime model for the voice                 |
| `voice.sessionMinutes`             | `15`                        | Voice devices: a conversation ends after this many idle minutes, the next question starts without the earlier history; `0` keeps the history like a chat |
| `voice.doorbell.enabled`           | `true`                      | Play a door bell sound on the voice devices when a door bell of home-devices rings |
| `voice.doorbell.devices`           | `[]`                        | Voice devices (id or room name) that ring; empty for all |
| `voice.doorbell.text`              | empty                       | Text Mirra says after the sound, e.g. `Es hat an der Tür geklingelt`; `{bell}` is the name of the door bell |
| `voice.model`                      | `gpt-4o-mini-tts`           | Speech endpoint model, used as fallback (shut down by OpenAI on 2027-01-06) |
| `voice.voice`                      | `nova`                      | Voice; best with Realtime: `marin`, `cedar` (voices Realtime does not have become `marin`) |
| `voice.instructions`               | `Speak in German, ...`      | How the voice should sound                   |
| `agent.shell.enabled`              | `true`                      | Offer `shell_run` to users with `SHELL`      |
| `agent.shell.timeoutSeconds`       | `120`                       | Default timeout of a shell command           |
| `agent.shell.automaticMaxLevel`    | `read`                      | Highest SSH level automatic runs (watchdog, tasks) may use: `read`, `ops`, `full` |
| `users.autoCreateGuests`           | `true`                      | Create a guest profile for unknown senders   |
| `users.requireApproval`            | `true`                      | New contacts need the approval of the approver |
| `users.approver`                   | `default.identityName`      | Profile that decides about new contacts and gets alerts and watchdog reports |
| `users.pendingMessage`             | `Hi! 👋 Ich bin Mirra ...`  | Answer to new contacts while pending         |
| `users.approvedMessage`            | `Du bist freigeschaltet ✅ ...` | Message after the approval               |
| `users.deniedMessage`              | empty                       | Message after a denial; empty sends nothing  |
| `autonomy.enabled`                 | `true`                      | Run the watchdog                             |
| `autonomy.mode`                    | `fix`                       | `fix` (act and report) or `report` (only analyze) |
| `autonomy.intervalMinutes`         | `5`                         | How often to look for new problems           |
| `autonomy.cooldownMinutes`         | `360`                       | Same problem at most once in this time       |
| `autonomy.maxRunsPerDay`           | `12`                        | Watchdog turns per day                       |
| `autonomy.ignore`                  | `[]`                        | Log texts to ignore (lower case)             |
| `memory.autoExtract`               | `true`                      | Extract long term memories after each message |
| `memory.summaryThreshold`          | `40`                        | Unsummarized messages that trigger a summary |
| `memory.contextSize`               | `12`                        | Long term memories added to each request     |
| `discord.token`                    | `xxxx`                      | Bot token; Discord is not started without it |
| `discord.defaultUID`               | `xxxx`                      | Discord user that receives notifications     |
| `whatsapp.defaultJID`              | `xxxx`                      | WhatsApp chat that receives notifications    |
| `evolutionApi.hostname`            | `http://localhost:8080`     | URL of the Evolution API                     |
| `evolutionApi.instance`            | `MirraAPI`                  | Instance name                                |
| `evolutionApi.apiKey`              | `xxxxxxxxxxxxxxxxxxxxxx`    | API key of the Evolution API                 |
| `rabbitMQ.hostname`                | `127.0.0.1`                 | RabbitMQ host for incoming messages          |
| `rabbitMQ.username`                | `mirra`                     | RabbitMQ user                                |
| `rabbitMQ.password`                | `password`                  | RabbitMQ password                            |
| `rabbitMQ.virtualHost`             | `/`                         | RabbitMQ virtual host                        |

Existing configurations keep working; new keys are added with their defaults on start. The weather tool uses
the API key of the [weather](https://github.com/LinzN/weather) plugin.

---

## Database

All tables are created automatically if they do not exist (MySQL/MariaDB); columns added in later versions are
added to existing tables.

| Table                                    | Columns                                                                      |
|------------------------------------------|------------------------------------------------------------------------------|
| `plugin_mirra_agent_user`                | `id`, `name`, `guest`, `access` (`APPROVED`, `PENDING`, `DENIED`), `full_name`, `nickname`, `birthday`, `language`, `timezone`, `location`, `preferred_channel`, `notes`, `created`, `updated`, `last_seen` |
| `plugin_mirra_agent_user_channel`        | `id`, `user_id`, `source` (`DISCORD`, `WHATSAPP`), `address`, `display_name`, `created`, `last_seen` |
| `plugin_mirra_agent_user_permission`     | `user_id`, `permission` (see [Permissions](#permissions-in-the-chat))        |
| `plugin_mirra_agent_user_attribute`      | `user_id`, `attr_key`, `attr_value`, `source` (`user`, `admin`, `auto`), `updated` |
| `plugin_mirra_agent_reminder`            | `id`, `user_id`, `channel_id`, `content`, `remind_at`, `active`, `created`   |
| `plugin_mirra_memory`                    | `id`, `model`, `identity`, `role`, `content`, `date`, `function_name`, `function_json` (chat history per channel) |
| `plugin_mirra_agent_summary`             | `model`, `chat_key`, `summary`, `covered_until`, `updated`                   |
| `plugin_mirra_agent_task`                | `id`, `owner_source`, `owner_token`, `owner_name`, `instruction`, `trigger_type`, `run_at`, `cron`, `event_type`, `device`, `match_value`, `topic`, `repeating`, `active`, `created`, `last_run`, `run_count`, `event_name`, `condition_field`, `condition_operator`, `condition_value` |
| `plugin_mirra_agent_memory`              | `id`, `owner` (`user:<id>`, `global`), `category`, `content`, `importance`, `embedding`, `embedding_model`, `source`, `created`, `updated`, `last_used`, `use_count` |
| `plugin_mirra_longterm_memory`           | `id`, `memory_data`, `date` (old memory, only read for the one-time import)  |

`address` is the Discord user id or the WhatsApp JID. Make yourself owner (all permissions) in the database
(Mirra picks it up after a restart; changes through Mirra apply immediately):

```sql
INSERT INTO plugin_mirra_agent_user_permission (user_id, permission)
SELECT id, 'OWNER' FROM plugin_mirra_agent_user WHERE name = 'Niklas';
```

---

## License

Copyright (c) MirraNET, Niklas Linz. Licensed under the
[GNU Lesser General Public License v3.0](https://www.gnu.org/licenses/lgpl-3.0.html).
