![MirraNET Logo](https://raw.githubusercontent.com/LinzN/Stem/master/stem-core/src/main/resources/header-logo.png)

# mirra

> **M**ultifunctional **I**ntelligent **R**eal-time **R**esponsive **A**ssistant — the AI assistant of [STEM](https://github.com/LinzN/Stem).

Mirra is an AI assistant based on OpenAI that talks to people on Discord and WhatsApp and controls STEM. She
remembers conversations, knows who she is talking to, switches lights, looks up the weather, creates
images and reminders and turns STEM system events into readable messages.

---

## Features

- **Chat** on Discord (private messages) and WhatsApp (via [Evolution API](https://github.com/LinzN/evolution-api-java))
- **Identities and permissions**: known users get their permissions from the database, unknown users are
  guests with minimal rights
- **Short term memory** per user (last 20 messages sent to the model, stored in the database)
- **Long term memory**: Mirra writes and searches facts in the database
- **Functions** the model can call:

| Function                       | Permission                 | Description                                        |
|--------------------------------|----------------------------|----------------------------------------------------|
| `get_stem_status`              | `STATUS_STEM`              | Uptime and status of STEM                          |
| `get_light`                    | `STATUS_LIGHT`             | Status of all lights                               |
| `set_light`                    | `SWITCH_LIGHT`             | Switches a light on or off                         |
| `get_weather`                  | `GET_WEATHER`              | Weather of a location                              |
| `create_image`                 | `CREATE_IMAGE`             | Generates an image and returns a link              |
| `create_reminder`              | `CREATE_REMINDER`          | Creates a reminder (registered users only)         |
| `access_long_term_memory`      | `LONG_TERM_MEMORY_ACCESS`  | Searches the long term memory                      |
| `write_long_term_memory`       | `LONG_TERM_MEMORY_WRITE`   | Stores a fact in the long term memory              |
| `set_mirra_status`             | no guests                  | Sets the Discord activity and a WhatsApp status    |
| `trigger_event`, `trigger_reminder`, `trigger_manual_function_call` | internal | Used by STEM itself only |

- **Reminders**: due reminders are sent to the user on Discord or WhatsApp
- **Notifications**: information blocks with the intent `NOTIFY_USER` are sent to the default Discord user
  and WhatsApp chat
- **AI text engine**: STEM can let Mirra phrase event texts
- **Social status**: once a day at 14:00 (and one minute after start) Mirra chooses a new status

---

## Requirements

| Requirement | Version                                                                                       |
|-------------|-----------------------------------------------------------------------------------------------|
| STEM        | 1.2.0 or newer (`stem-api`), database module configured                                       |
| Plugins     | [home-devices](https://github.com/LinzN/home-devices), [weather](https://github.com/LinzN/weather), [gas-station](https://github.com/LinzN/gas-station) |
| OpenAI      | API key                                                                                       |
| Discord     | Bot token with the **Message Content** intent (optional)                                      |
| WhatsApp    | Evolution API instance with RabbitMQ (optional)                                               |

---

## Installation

1. Build the plugin with `mvn clean package` (or take the build from Jenkins).
2. Copy `target/mirra.jar` into the `plugins` folder of STEM and the libraries from
   `target/libraries` as `libraries.zip` (folder `libraries` inside) into `plugins/mirra/`.
3. Make sure the database tables exist (see [Database](#database)).
4. Start STEM once to create `plugins/mirra/config.yml`, fill it in and restart STEM.

---

## Configuration (`config.yml`)

| Key                                | Default                  | Description                                 |
|------------------------------------|--------------------------|---------------------------------------------|
| `openAI.token`                     | `xxxx`                   | OpenAI API key                              |
| `default.model`                    | `mirra`                  | Name of the model configuration below       |
| `default.identityName`             | `Niklas`                 | Name used as sender for STEM events         |
| `model.<name>.textOpenAIModel`     | `test123`                | OpenAI chat model, e.g. `gpt-4o`            |
| `model.<name>.imageOpenAIModel`    | `test123`                | OpenAI image model, e.g. `dall-e-3`         |
| `model.<name>.aiRoleDescription`   | `You are an assistant`   | System prompt (personality of Mirra)        |
| `discord.token`                    | `xxxx`                   | Bot token; Discord is not started without it |
| `discord.defaultUID`               | `xxxx`                   | Discord user that receives notifications    |
| `whatsapp.defaultJID`              | `xxxx`                   | WhatsApp chat that receives notifications   |
| `evolutionApi.hostname`            | `http://localhost:8080`  | URL of the Evolution API                    |
| `evolutionApi.instance`            | `MirraAPI`               | Instance name                               |
| `evolutionApi.apiKey`              | `xxxxxxxxxxxxxxxxxxxxxx` | API key of the Evolution API                |
| `rabbitMQ.hostname`                | `127.0.0.1`              | RabbitMQ host for incoming messages         |
| `rabbitMQ.username`                | `mirra`                  | RabbitMQ user                               |
| `rabbitMQ.password`                | `password`               | RabbitMQ password                           |
| `rabbitMQ.virtualHost`             | `/`                      | RabbitMQ virtual host                       |

The weather function uses the API key of the [weather](https://github.com/LinzN/weather) plugin.

---

## Database

The tables must exist; they are not created automatically.

| Table                                  | Columns used                                                                 |
|----------------------------------------|------------------------------------------------------------------------------|
| `plugin_mirra_identity_user`           | `id`, `identity_name`                                                        |
| `plugin_mirra_identity_user_tokens`    | `id`, `identity_user_id`, `identity_token`, `token_source` (`DISCORD`, `WHATSAPP`) |
| `plugin_mirra_identity_user_permissions` | `identity_user_id`, `permission` (see functions table)                     |
| `plugin_mirra_memory`                  | `id`, `model`, `identity`, `role`, `content`, `date`, `function_name`, `function_json` |
| `plugin_mirra_longterm_memory`         | `id`, `memory_data`, `date`                                                  |
| `plugin_mirra_reminder`                | `id`, `user_token_id`, `reminder_content`, `reminder_datetime`, `active`, `created` |

`identity_token` is the Discord user id or the WhatsApp JID. Users without an entry are guests: they can
chat and use `get_stem_status`, nothing else.

---

## License

Copyright (c) MirraNET, Niklas Linz. Licensed under the
[GNU Lesser General Public License v3.0](https://www.gnu.org/licenses/lgpl-3.0.html).
