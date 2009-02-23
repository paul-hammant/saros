/*
 * DPP - Serious Distributed Pair Programming
 * (c) Freie Universitaet Berlin - Fachbereich Mathematik und Informatik - 2006
 * (c) Riad Djemili - 2006
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 1, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package de.fu_berlin.inf.dpp.activities;

import de.fu_berlin.inf.dpp.User.UserRole;
import de.fu_berlin.inf.dpp.net.JID;

public class RoleActivity implements IActivity {

    private String source;

    private final JID user;
    private final UserRole role;

    public RoleActivity(JID user, UserRole role) {
        this.user = user;
        this.role = role;
    }

    public JID getUser() {
        return this.user;
    }

    public UserRole getRole() {
        return role;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof RoleActivity) {
            RoleActivity activity = (RoleActivity) obj;
            return activity.getUser().equals(this.user);
        }
        return false;
    }

    @Override
    public String toString() {
        return "RoleActivity(user:" + this.user + ",role:" + this.getRole()
            + ")";
    }

    public String getSource() {
        return this.source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
