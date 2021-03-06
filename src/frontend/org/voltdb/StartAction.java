/* This file is part of VoltDB.
 * Copyright (C) 2008-2013 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public enum StartAction {

    CREATE("create"),
    RECOVER("recover"),
    SAFE_RECOVER("recover safemode"),
    REJOIN("rejoin"),
    LIVE_REJOIN("live rejoin"),
    JOIN("add");

    final static Pattern spaces = Pattern.compile("\\s+");

    final static Map<String, StartAction> verbMoniker =
            new HashMap<String, StartAction>();

    final static EnumSet<StartAction> recoverSet =
            EnumSet.of(RECOVER,SAFE_RECOVER);

    final static EnumSet<StartAction> rejoinSet =
            EnumSet.of(REJOIN,LIVE_REJOIN);

    final static EnumSet<StartAction> joinSet =
            EnumSet.of(REJOIN,LIVE_REJOIN,JOIN);

    final String m_verb;

    static {
        for (StartAction action: StartAction.values()) {
            verbMoniker.put(action.m_verb, action);
        }
    }

    StartAction(String verb) {
        m_verb = verb;
    }

    public static StartAction monickerFor(String verb) {
        if (verb == null) return null;
        verb = spaces.matcher(verb.trim().toLowerCase()).replaceAll(" ");
        return verbMoniker.get(verb);
    }

    public String verb() {
        return m_verb;
    }

    public boolean doesRecover() {
        return recoverSet.contains(this);
    }

    public boolean doesRejoin() {
        return rejoinSet.contains(this);
    }

    public boolean doesJoin() {
        return joinSet.contains(this);
    }
}
