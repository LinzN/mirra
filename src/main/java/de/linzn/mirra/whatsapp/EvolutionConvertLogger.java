/*
 * Copyright (c) 2026 MirraNET, Niklas Linz. All rights reserved.
 *
 * This file is part of the MirraNET project and is licensed under the
 * GNU Lesser General Public License v3.0 (LGPLv3).
 *
 * You may use, distribute and modify this code under the terms
 * of the LGPLv3 license. You should have received a copy of the
 * license along with this file. If not, see <https://www.gnu.org/licenses/lgpl-3.0.html>
 * or contact: niklas.linz@mirranet.de
 */

package de.linzn.mirra.whatsapp;

import de.linzn.evolutionApiJava.logger.EvolutionLogger;
import de.linzn.stem.modules.pluginModule.STEMPlugin;

public class EvolutionConvertLogger implements EvolutionLogger {

    private final STEMPlugin plugin;

    public EvolutionConvertLogger(STEMPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void DEBUG(Object o) {
        this.plugin.getPluginLogger().debug(String.valueOf(o));
    }

    @Override
    public void INFO(Object o) {
        this.plugin.getPluginLogger().info(String.valueOf(o));
    }

    @Override
    public void WARNING(Object o) {
        this.plugin.getPluginLogger().warning(String.valueOf(o));
    }

    @Override
    public void ERROR(Object o) {
        if (o instanceof Throwable throwable) {
            this.plugin.getPluginLogger().error(throwable);
        } else {
            this.plugin.getPluginLogger().error(String.valueOf(o));
        }
    }
}
