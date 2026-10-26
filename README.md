![MirraNET Logo](https://raw.githubusercontent.com/LinzN/Stem/master/stem-core/src/main/resources/header-logo.png)

# mirra

> **M**ultifunctional **I**ntelligent **R**eal-time **R**esponsive **A**ssistant — the AI assistant of [STEM](https://github.com/LinzN/Stem).

Mirra is an AI agent based on the OpenAI [Responses API](https://platform.openai.com/docs/api-reference/responses).
She talks to people on Discord and WhatsApp, controls STEM and its smart home, remembers what people tell her
across chats and turns STEM system events into readable messages.

---

## Features

- **Chat** on Discord (private messages) and WhatsApp (via [Evolution API](https://github.com/LinzN/evolution-api-java))
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
- **Voice**: text to speech with an OpenAI voice, optionally as answer to every voice message
- **Tasks**: Mirra plans tasks for later, on a schedule or on events (e.g. "switch the 3D printer off when the
  print is finished") and carries them out herself (see [Tasks](#tasks))
- **Shell**: commands on the STEM server and via SSH on other machines (separate `SHELL` permission)
- **Memory** in three layers (see [Memory](#memory))
- **Reminders**: due reminders are sent to the user on Discord or WhatsApp
- **Notifications**: information blocks with the intent `NOTIFY_USER` are sent to the default Discord user
  and WhatsApp chat
- **AI text engine**: STEM can let Mirra phrase event texts
- **Social status**: Mirra chooses a new Discord/WhatsApp status on a schedule
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
| `stem_status`             | everybody                                   | Version, uptime, memory, threads of STEM            |
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
| `stem_data_set`           | `ADMIN`                                      | Writes a JSON container of the STEM data store      |
| `stem_sql`                | `ADMIN`                                      | Runs an SQL statement on the STEM database (max. 200 rows) |
| `stem_mqtt_read`          | `STEM_VIEW`                                  | Listens to MQTT topics for a few seconds            |
| `list_users`              | `STEM_VIEW`                                  | User profiles and their channels (guests optional)  |
| `get_user_profile`        | own profile; others `STEM_VIEW`              | Profile fields, attributes, channels, permissions   |
| `update_user_profile`     | own profile; others `ADMIN`                  | Sets or deletes a profile field or attribute        |
| `list_access_requests`    | `ADMIN`                                      | People waiting for approval                         |
| `decide_access_request`   | `ADMIN`                                      | Approves or denies (blocks) a person                |
| `create_user`             | `ADMIN`                                      | Creates a registered user                           |
| `link_user_channel`       | `ADMIN`                                      | Adds a Discord id or WhatsApp number to a user (merges guest profiles) |
| `unlink_user_channel`     | `ADMIN`                                      | Removes a channel from a user                       |
| `list_permissions`        | `ADMIN`                                      | All permissions with meaning, unlocked tools and holders |
| `set_user_permissions`    | `ADMIN`      (`SHELL` only by users with `SHELL`) | Grants and revokes several permissions, optionally tells the user |
| `set_user_registered`     | `ADMIN`                                      | Turns a guest into a registered user or back        |
| `merge_users`             | `ADMIN`                                      | Merges two profiles of the same person              |
| `create_task`             | registered users                             | Plans a task at a time, on a cron schedule or on an event |
| `list_event_types`        | `STEM_VIEW`                                  | STEM events seen so far with example data           |
| `list_tasks`              | registered users (all users: `ADMIN`)   | Planned tasks                                       |
| `cancel_task`             | owner or `ADMIN`                             | Cancels a planned task                              |
| `shell_run`               | `SHELL`                                      | Shell command on the STEM server or via SSH         |
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
| `set_mirra_status`        | no guests                                    | Sets the Discord activity and a WhatsApp status     |

Higher permissions include lower ones (see [Permissions](#permissions-in-the-chat)). `SHELL` is separate and not
part of `ADMIN`; give it only to people who may run any command on the server. SSH uses the key based login of the user STEM runs as (`BatchMode`, no
password prompts). Guests have no permissions; everybody can ask for the STEM status. Console commands listed in `agent.blockedCommands` are
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
`HOME_VIEW`, `STEM_ADMIN` → `ADMIN`; `STATUS_STEM` is dropped (everybody may ask for the status).

Unknown senders get a guest profile named after their chat name (`users.autoCreateGuests`). An administrator can
ask Mirra to register a guest, add channels ("Die WhatsApp-Nummer 49176... gehört zu Anna"), merge two profiles
of the same person or grant permissions.

### Migration from earlier versions

On the first start the old identity tables are copied into the new tables in one transaction
(only while there are no profiles yet, `users.migrated` is set afterwards):

| Old                                      | New                                   |
|------------------------------------------|---------------------------------------|
| `plugin_mirra_agent_user`                | `id`, `name`, `guest`, `access` (`APPROVED`, `PENDING`, `DENIED`), `full_name`, `nickname`, `birthday`, `language`, `timezone`, `location`, `preferred_channel`, `notes`, `created`, `updated`, `last_seen` |
| `plugin_mirra_agent_user_channel`        | `id`, `user_id`, `source` (`DISCORD`, `WHATSAPP`), `address`, `display_name`, `created`, `last_seen` |
| `plugin_mirra_agent_user_permission`     | `user_id`, `permission` (see [Tools](#tools))                                |
| `plugin_mirra_agent_user_attribute`      | `user_id`, `attr_key`, `attr_value`, `source` (`user`, `admin`, `auto`), `updated` |
| `plugin_mirra_agent_reminder`            | `id`, `user_id`, `channel_id`, `content`, `remind_at`, `active`, `created`   |
| `plugin_mirra_memory`                    | `id`, `model`, `identity`, `role`, `content`, `date`, `function_name`, `function_json` (chat history per channel) |
| `plugin_mirra_agent_summary`             | `model`, `chat_key`, `summary`, `covered_until`, `updated`                   |
| `plugin_mirra_agent_task`                | `id`, `owner_source`, `owner_token`, `owner_name`, `instruction`, `trigger_type`, `run_at`, `cron`, `event_type`, `device`, `match_value`, `topic`, `repeating`, `active`, `created`, `last_run`, `run_count`, `event_name`, `condition_field`, `condition_operator`, `condition_value` |
| `plugin_mirra_agent_memory`              | `id`, `owner` (`user:<id>`, `global`), `category`, `content`, `importance`, `embedding`, `embedding_model`, `source`, `created`, `updated`, `last_used`, `use_count` |
| `plugin_mirra_longterm_memory`           | `id`, `memory_data`, `date` (old memory, only read for the one-time import)  |

`address` is the Discord user id or the WhatsApp JID. Example to make yourself owner (all permissions) in the database
(Mirra picks it up after a restart; through Mirra it applies immediately):

```sql
INSERT INTO plugin_mirra_agent_user_permission (user_id, permission)
SELECT id, 'OWNER' FROM plugin_mirra_agent_user WHERE name = 'Niklas';
```

---

## License

Copyright (c) MirraNET, Niklas Linz. Licensed under the
[GNU Lesser General Public License v3.0](https://www.gnu.org/licenses/lgpl-3.0.html).
