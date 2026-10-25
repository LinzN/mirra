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
- **Identities and permissions**: known users get their permissions from the database, unknown users are
  guests with minimal rights; tools a user may not use are not offered to the model at all
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
| `stem_status`             | `STATUS_STEM` or `STEM_READ`                 | Version, uptime, memory, threads of STEM            |
| `stem_list_plugins`       | `STEM_READ`                                  | Loaded plugins                                      |
| `stem_health_checks`      | `STEM_READ`                                  | Results of the health checks                        |
| `stem_information_blocks` | `STEM_READ`                                  | Active information blocks                           |
| `stem_notifications`      | `STEM_READ`                                  | Recent notifications                                |
| `stem_list_commands`      | `STEM_READ`                                  | Registered console commands                         |
| `stem_run_health_checks`  | `STEM_ADMIN`                                 | Runs all health checks now                          |
| `stem_run_command`        | `STEM_ADMIN`                                 | Runs a console command and returns its output       |
| `stem_run_script`         | `STEM_ADMIN`                                 | Runs a script of the STEM script engine             |
| `stem_mqtt_publish`       | `STEM_ADMIN`                                 | Publishes an MQTT message                           |
| `stem_push_notification`  | `STEM_ADMIN`                                 | Sends a STEM notification                           |
| `stem_show_information`   | `STEM_ADMIN`                                 | Shows an information block                          |
| `home_list_devices`       | `STEM_READ`                                  | Devices of home-devices with state                  |
| `home_get_device`         | `STEM_READ`                                  | Details of one device                               |
| `home_get_lights`         | `STATUS_LIGHT` or `STEM_READ`                | State of all lights                                 |
| `home_control_device`     | `STEM_ADMIN` (lights also with `SWITCH_LIGHT`) | Switches or dims a device                         |
| `home_get_profile`        | `STEM_READ`                                  | Active smart home profile                           |
| `home_set_profile`        | `STEM_ADMIN`                                 | Changes the smart home profile                      |
| `get_weather`             | `GET_WEATHER`                                | Weather of a location                               |
| `get_fuel_prices`         | `GET_FUEL_PRICE`                             | Fuel prices                                         |
| `create_reminder`, `list_reminders`, `cancel_reminder` | `CREATE_REMINDER`, registered users | Reminders                    |
| `remember`, `forget`      | `LONG_TERM_MEMORY_WRITE`                     | Stores or deletes a long term memory                |
| `recall`                  | `LONG_TERM_MEMORY_ACCESS` or `LONG_TERM_MEMORY_WRITE` | Searches the long term memory              |
| `create_image`            | `CREATE_IMAGE`                               | Generates an image and returns a link               |
| `set_mirra_status`        | no guests                                    | Sets the Discord activity and a WhatsApp status     |

`STEM_ADMIN` includes `STEM_READ`. Guests only have `STATUS_STEM`. Console commands listed in
`agent.blockedCommands` are never run.

---

## Memory

| Layer            | Scope                       | Storage                          | How it works                                                     |
|------------------|-----------------------------|----------------------------------|------------------------------------------------------------------|
| Chat history     | one chat (user token)       | `plugin_mirra_memory`            | The last `agent.historyMessages` messages are sent with each request |
| Chat summary     | one chat                    | `plugin_mirra_agent_summary`     | When `memory.summaryThreshold` messages are not summarized yet, the older ones are merged into a running summary |
| Long term memory | one person or the household | `plugin_mirra_agent_memory`      | Facts, preferences, people, events and plans with importance 1–5 and an embedding |

After every answer a background job asks the memory model which lasting facts the exchange contains and
adds, updates or deletes long term memories (`memory.autoExtract`). Memories belong to the identity, so
what Niklas tells Mirra on WhatsApp is also known on Discord. Guests get their own memory per token.
Household facts are stored as `global` and are visible to everyone with `LONG_TERM_MEMORY_WRITE`.

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
| STEM        | 1.2.0 or newer with `CommandService.execute` (branch `agent`), database module configured     |
| Plugins     | [home-devices](https://github.com/LinzN/home-devices), [weather](https://github.com/LinzN/weather), [gas-station](https://github.com/LinzN/gas-station) |
| OpenAI      | API key with access to the Responses, Embeddings and Images API                               |
| Discord     | Bot token with the **Message Content** intent (optional)                                      |
| WhatsApp    | Evolution API instance with RabbitMQ (optional)                                               |

---

## Installation

1. Build the plugin with `mvn clean package` (or take the build from Jenkins).
2. Copy `target/mirra.jar` into the `plugins` folder of STEM and the libraries from
   `target/libraries` as `libraries.zip` (folder `libraries` inside) into `plugins/mirra/`.
3. Start STEM once to create `plugins/mirra/config.yml` and the database tables, fill in the config and
   restart STEM.
4. Give your identities the new permissions `STEM_READ` or `STEM_ADMIN` (see [Database](#database)).

---

## Configuration (`config.yml`)

| Key                                | Default                     | Description                                  |
|------------------------------------|-----------------------------|----------------------------------------------|
| `openAI.token`                     | `xxxx`                      | OpenAI API key                               |
| `openAI.baseUrl`                   | `https://api.openai.com/v1` | API base URL (e.g. for a proxy)              |
| `openAI.timeoutSeconds`            | `120`                       | Timeout of one request                       |
| `default.model`                    | `mirra`                     | Name of the model configuration below        |
| `default.identityName`             | `Niklas`                    | Name used as sender for STEM events          |
| `model.<name>.textOpenAIModel`     | `gpt-4.1`                   | Chat model, e.g. `gpt-4.1`, `gpt-5`          |
| `model.<name>.imageOpenAIModel`    | `dall-e-3`                  | Image model, e.g. `dall-e-3`, `gpt-image-1`  |
| `model.<name>.aiRoleDescription`   | `You are Mirra, ...`        | Personality of Mirra                         |
| `model.<name>.memoryModel`         | text model                  | Model for memory extraction and summaries    |
| `model.<name>.embeddingModel`      | `text-embedding-3-small`    | Embedding model of the long term memory      |
| `model.<name>.reasoningEffort`     | empty                       | `low`, `medium` or `high` for reasoning models, empty for others |
| `agent.maxToolRounds`              | `10`                        | Tool rounds per message                      |
| `agent.historyMessages`            | `16`                        | Chat messages sent with each request         |
| `agent.webSearch`                  | `false`                     | Offer the OpenAI web search tool to registered users |
| `agent.blockedCommands`            | `[stop, reboot]`            | Console commands Mirra must not run          |
| `agent.commandTimeoutSeconds`      | `60`                        | Timeout of a console command                 |
| `agent.scriptTimeoutSeconds`       | `120`                       | Timeout of a script                          |
| `agent.socialStatus.enabled`       | `true`                      | Let Mirra choose a social status             |
| `agent.socialStatus.cron`          | `0 14 * * *`                | When to choose a new status                  |
| `agent.socialStatus.onStartup`     | `true`                      | Also choose one after start                  |
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

All tables are created automatically if they do not exist (MySQL/MariaDB).

| Table                                    | Columns                                                                      |
|------------------------------------------|------------------------------------------------------------------------------|
| `plugin_mirra_identity_user`             | `id`, `identity_name`                                                        |
| `plugin_mirra_identity_user_tokens`      | `id`, `identity_user_id`, `identity_token`, `token_source` (`DISCORD`, `WHATSAPP`) |
| `plugin_mirra_identity_user_permissions` | `identity_user_id`, `permission` (see [Tools](#tools))                       |
| `plugin_mirra_memory`                    | `id`, `model`, `identity`, `role`, `content`, `date`, `function_name`, `function_json` |
| `plugin_mirra_agent_summary`             | `model`, `chat_key`, `summary`, `covered_until`, `updated`                   |
| `plugin_mirra_agent_memory`              | `id`, `owner`, `category`, `content`, `importance`, `embedding`, `embedding_model`, `source`, `created`, `updated`, `last_used`, `use_count` |
| `plugin_mirra_longterm_memory`           | `id`, `memory_data`, `date` (old memory, only read for the one-time import)  |
| `plugin_mirra_reminder`                  | `id`, `user_token_id`, `reminder_content`, `reminder_datetime`, `active`, `created` |

`identity_token` is the Discord user id or the WhatsApp JID. Example to grant full STEM control:

```sql
INSERT INTO plugin_mirra_identity_user_permissions (identity_user_id, permission) VALUES (1, 'STEM_ADMIN');
```

---

## License

Copyright (c) MirraNET, Niklas Linz. Licensed under the
[GNU Lesser General Public License v3.0](https://www.gnu.org/licenses/lgpl-3.0.html).
