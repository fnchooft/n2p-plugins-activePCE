/*
 * Copyright (c) $year Jose-Juan Pedreno-Manresa Jose-Luis Izquierdo-Zaragoza Pablo Pavon-Marino, .
 *   All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the GNU Lesser Public License v3
 *  which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 *  Contributors:
 */

package es.upct.girtel.net2plan.plugins.abnopce.pce.bgp;

import com.net2plan.internal.ErrorHandling;
import es.tid.bgp.bgp4.messages.*;
import es.tid.bgp.bgp4.open.BGP4CapabilitiesOptionalParameter;
import es.tid.bgp.bgp4.open.BGP4Capability;
import es.tid.bgp.bgp4.open.BGP4OptionalParameter;
import es.tid.bgp.bgp4.open.MultiprotocolExtensionCapabilityAdvertisement;
import es.tid.bgp.bgp4.update.fields.pathAttributes.AFICodes;
import es.tid.bgp.bgp4.update.fields.pathAttributes.SAFICodes;
import es.tid.pce.pcep.messages.PCEPMessage;
import es.upct.girtel.net2plan.plugins.abnopce.pce.PCEMasterController;
import es.upct.girtel.net2plan.plugins.abnopce.utils.RequestHandler;
import es.upct.girtel.net2plan.plugins.abnopce.utils.Utils;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Socket;
import java.util.LinkedList;
import java.util.List;

/**
 * Created by Kuranes on 03/04/2015.
 */
public class BGPHandler extends RequestHandler
{
	private boolean _keepAlive;

	public BGPHandler(Socket socket) throws IOException
	{
		super(socket);
		_keepAlive = true;
		_ipAddress = _socket.getInetAddress();
	}

	@Override
	public void doService()
	{
		/* Receive first message and check if it's BGPOpen */
		try
		{
			byte[] receivedBytes = Utils.readBGP4Msg(_input);

			int messageType = BGP4Message.getMessageType(receivedBytes);
			if (messageType != BGP4MessageTypes.MESSAGE_OPEN)
			{
				_socket.close();
				System.out.println("(BGP/LS-Open) Wrong first message, quitting...");
				return;
			}
			
			/* Registering with Master Controller */
			PCEMasterController.getInstance().registerBGP(this, _ipAddress);

			System.out.println("BGP/LS First message received");
			
			BGP4Open request = new BGP4Open(receivedBytes);
			System.out.println(request);
			
			/* Returning open message */
			BGP4Open response = new BGP4Open(); //TODO negotiate values
			response.setBGPIdentifier((Inet4Address) _socket.getLocalAddress());
			
			/* Add Link-State NLRI capability advertisement to BGP OPEN message */
			LinkedList<BGP4OptionalParameter> bgpOpenParameterList = new LinkedList<BGP4OptionalParameter>();
			MultiprotocolExtensionCapabilityAdvertisement linkStateNLRICapabilityAdvertisement = new MultiprotocolExtensionCapabilityAdvertisement();
			linkStateNLRICapabilityAdvertisement.setAFI(AFICodes.AFI_BGP_LS);
			linkStateNLRICapabilityAdvertisement.setSAFI(SAFICodes.SAFI_BGP_LS);
			BGP4CapabilitiesOptionalParameter linkStateNLRICapabilityParameter = new BGP4CapabilitiesOptionalParameter();
			LinkedList<BGP4Capability> capabilityList = new LinkedList<BGP4Capability>();
			capabilityList.add(linkStateNLRICapabilityAdvertisement);
			linkStateNLRICapabilityParameter.setCapabilityList(capabilityList);
			bgpOpenParameterList.add(linkStateNLRICapabilityParameter);
			response.setParametersList(bgpOpenParameterList);
			
			response.encode();
			_output.write(response.getBytes());

			receivedBytes = Utils.readBGP4Msg(_input);
			messageType = BGP4Message.getMessageType(receivedBytes);
			if (messageType != BGP4MessageTypes.MESSAGE_KEEPALIVE)
			{
				_socket.close();
				System.out.println("(BGP/LS-Keepalive) Wrong first message, quitting...");
				return;
			}
			
			System.out.println("BGP: First Keep-Alive message received");
			
			BGP4Keepalive keekaliveMsg = new BGP4Keepalive();
			keekaliveMsg.encode();
			Utils.writeMessage(_output, keekaliveMsg.getBytes());

		} catch (Exception e) {e.printStackTrace();}

		/* Infinite loop now */
		while (_keepAlive)
		{
			/* Receive message and check its type */
			try
			{
				byte[] receivedBytes = Utils.readBGP4Msg(_input);
				if (receivedBytes == null) shutdown();

				int messageType = BGP4Message.getMessageType(receivedBytes);
				switch (messageType)
				{
					case BGP4MessageTypes.MESSAGE_UPDATE:
						BGP4Update message = new BGP4Update(receivedBytes);
						message.decode();
						
						List outMsg = new LinkedList();
						
						try
						{
							outMsg = PCEMasterController.getInstance().getPCEEntity(_ipAddress).handleBGPMessage(message);
						}
						catch(Throwable e)
						{
							System.out.println(">> Error handling BGPUpdate");
							e.printStackTrace(); //DEBUG
							ErrorHandling.addErrorOrException(e, BGPHandler.class);
						}
						
						for(Object msg : outMsg)
						{
							if (msg instanceof BGP4Message)
								Utils.writeMessage(_output, ((BGP4Message) msg).getBytes()); //WTF??
							else if (msg instanceof PCEPMessage)
								Utils.writeMessage(PCEMasterController.getInstance().getPCEEntity(_ipAddress).getPCEPHandler().getOutputStream(), ((PCEPMessage) msg).getBytes());
						}
						
						break;

					default:
						System.out.println("Message of type " + messageType + " received! Nothing to do for the moment...");
				}

			} catch (Exception e)
			{
				e.printStackTrace();
				_keepAlive = false;
			}

		}//End of infinite while
		System.out.println("Ending BGPHandler");
	}

	private void shutdown()
	{
		_keepAlive = false;
		try
		{
			_socket.close();
		} catch (IOException e)
		{
			e.printStackTrace();
		}

	}
}

