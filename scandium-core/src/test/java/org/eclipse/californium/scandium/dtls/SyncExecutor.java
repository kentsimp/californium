/*******************************************************************************
 * Copyright (c) 2021 Bosch.IO GmbH and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *    Bosch IO.GmbH - initial creation
 ******************************************************************************/
package org.eclipse.californium.scandium.dtls;

import java.util.concurrent.Executor;

/**
 * Synchronous Executor.
 * 
 * Executes command synchronous to simplify unit tests.
 * 
 * @since 3.0 (replaces SyncSerialExecutor)
 */
public class SyncExecutor implements Executor {

	/**
	 * Execute the job synchronous.
	 */
	@Override
	public void execute(final Runnable command) {
		command.run();
	}
}
