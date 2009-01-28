/**
 * This file is part of the Kompics component model runtime.
 * 
 * Copyright (C) 2009 Swedish Institute of Computer Science (SICS)
 * Copyright (C) 2009 Royal Institute of Technology (KTH)
 *
 * Kompics is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package se.sics.kompics.network.mina;

import se.sics.kompics.Init;
import se.sics.kompics.address.Address;

/**
 * The <code>MinaNetworkInit</code> class.
 * 
 * @author Cosmin Arad <cosmin@sics.se>
 * @author Jim Dowling <jdowling@sics.se>
 * @version $Id: MinaNetworkInit.java 268 2008-09-28 19:18:04Z Cosmin $
 */
public final class MinaNetworkInit extends Init {

	/** The self. */
	private final Address self;

	/** The connect retries. */
	private final int connectRetries;

	/**
	 * Instantiates a new mina network init.
	 * 
	 * @param self
	 *            the self
	 */
	public MinaNetworkInit(Address self) {
		this(self, 0);
	}

	/**
	 * Instantiates a new mina network init.
	 * 
	 * @param self
	 *            the self
	 * @param connectRetries
	 *            the connect retries
	 */
	public MinaNetworkInit(Address self, int connectRetries) {
		super();
		this.self = self;
		this.connectRetries = connectRetries;
	}

	/**
	 * Gets the self.
	 * 
	 * @return the self
	 */
	public final Address getSelf() {
		return self;
	}

	/**
	 * Gets the connect retries.
	 * 
	 * @return the connect retries
	 */
	public int getConnectRetries() {
		return connectRetries;
	}
}
