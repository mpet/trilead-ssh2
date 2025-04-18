
package com.trilead.ssh2.transport;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.trilead.ssh2.ConnectionInfo;
import com.trilead.ssh2.DHGexParameters;
import com.trilead.ssh2.ExtendedServerHostKeyVerifier;
import com.trilead.ssh2.ServerHostKeyVerifier;
import com.trilead.ssh2.crypto.CryptoWishList;
import com.trilead.ssh2.crypto.KeyMaterial;
import com.trilead.ssh2.crypto.cipher.BlockCipher;
import com.trilead.ssh2.crypto.cipher.BlockCipherFactory;
import com.trilead.ssh2.crypto.dh.Curve25519Exchange;
import com.trilead.ssh2.crypto.dh.DhGroupExchange;
import com.trilead.ssh2.crypto.dh.GenericDhExchange;
import com.trilead.ssh2.crypto.digest.MessageMac;
import com.trilead.ssh2.log.Logger;
import com.trilead.ssh2.packets.PacketKexDHInit;
import com.trilead.ssh2.packets.PacketKexDHReply;
import com.trilead.ssh2.packets.PacketKexDhGexGroup;
import com.trilead.ssh2.packets.PacketKexDhGexInit;
import com.trilead.ssh2.packets.PacketKexDhGexReply;
import com.trilead.ssh2.packets.PacketKexDhGexRequest;
import com.trilead.ssh2.packets.PacketKexDhGexRequestOld;
import com.trilead.ssh2.packets.PacketKexInit;
import com.trilead.ssh2.packets.PacketNewKeys;
import com.trilead.ssh2.packets.Packets;
import com.trilead.ssh2.signature.KeyAlgorithm;
import com.trilead.ssh2.signature.KeyAlgorithmManager;


/**
 * KexManager.
 * 
 * @author Christian Plattner, plattner@trilead.com
 * @version $Id: KexManager.java,v 1.1 2007/10/15 12:49:56 cplattne Exp $
 */
public class KexManager implements MessageHandler
{
	private static final Logger log = Logger.getLogger(KexManager.class);
	private static final String PROPERTY_TIMEOUT = KexManager.class.getName() + ".timeout";
	private static long DEFAULT_WAIT_TIMEOUT = Long.parseLong(System.getProperty(PROPERTY_TIMEOUT,"1200000"));

	private static final List<String> DEFAULT_KEY_ALGORITHMS = buildDefaultKeyAlgorithms();

	KexState kxs;
	int kexCount = 0;
	KeyMaterial km;
	byte[] sessionId;
	ClientServerHello csh;

	final Object accessLock = new Object();
	ConnectionInfo lastConnInfo = null;

	boolean connectionClosed = false;

	boolean ignore_next_kex_packet = false;

	final TransportManager tm;

	CryptoWishList nextKEXcryptoWishList;
	DHGexParameters nextKEXdhgexParameters;

	ServerHostKeyVerifier verifier;
	final String hostname;
	final int port;
	final SecureRandom rnd;

	public KexManager(TransportManager tm, ClientServerHello csh, CryptoWishList initialCwl, String hostname, int port,
			ServerHostKeyVerifier keyVerifier, SecureRandom rnd)
	{
		this.tm = tm;
		this.csh = csh;
		this.nextKEXcryptoWishList = initialCwl;
		this.nextKEXdhgexParameters = new DHGexParameters();
		this.hostname = hostname;
		this.port = port;
		this.verifier = keyVerifier;
		this.rnd = rnd;
	}

	public ConnectionInfo getOrWaitForConnectionInfo(int minKexCount) throws IOException
	{
		synchronized (accessLock)
		{
			while (true)
			{
				if ((lastConnInfo != null) && (lastConnInfo.keyExchangeCounter >= minKexCount))
					return lastConnInfo;

				if (connectionClosed)
					throw new IOException("Key exchange was not finished, connection is closed.", tm.getReasonClosedCause());

				try
				{
					accessLock.wait(DEFAULT_WAIT_TIMEOUT);
				}
				catch (InterruptedException e)
				{
					throw new InterruptedIOException();
				}
			}
		}
	}

	private String getFirstMatch(String[] client, String[] server) throws NegotiateException
	{
		if (client == null || server == null)
			throw new IllegalArgumentException();

		if (client.length == 0)
			return null;

		for (String aClient : client) {
			for (String aServer : server) {
				if (aClient.equals(aServer))
					return aClient;
			}
		}
		throw new NegotiateException("No matching algorithm found. "+"Client: " + Arrays.toString(sortedArray(client)) + ", Server: " + Arrays.toString(sortedArray(server)));
	}

	private static String[] sortedArray(String[] array) 
	{
    if (array == null) {
        return new String[]{"<empty>"};
    }
    Arrays.sort(array);
    return array;
	}

	private boolean compareFirstOfNameList(String[] a, String[] b)
	{
		if (a == null || b == null)
			throw new IllegalArgumentException();

		if ((a.length == 0) && (b.length == 0))
			return true;

		if ((a.length == 0) || (b.length == 0))
			return false;

		return (a[0].equals(b[0]));
	}

	private boolean isGuessOK(KexParameters cpar, KexParameters spar)
	{
		if (cpar == null || spar == null)
			throw new IllegalArgumentException();

		if (!compareFirstOfNameList(cpar.kex_algorithms, spar.kex_algorithms))
		{
			return false;
		}

		if (!compareFirstOfNameList(cpar.server_host_key_algorithms, spar.server_host_key_algorithms))
		{
			return false;
		}

		/*
		 * We do NOT check here if the other algorithms can be agreed on, this
		 * is just a check if kex_algorithms and server_host_key_algorithms were
		 * guessed right!
		 */

		return true;
	}

	NegotiatedParameters mergeKexParameters(KexParameters client, KexParameters server) throws NegotiateException
	{
		NegotiatedParameters np = new NegotiatedParameters();

		
			np.kex_algo = getFirstMatch(client.kex_algorithms, server.kex_algorithms);

			log.log(30, "kex_algo=" + np.kex_algo);

			np.server_host_key_algo = getFirstMatch(client.server_host_key_algorithms,
					server.server_host_key_algorithms);

			log.log(30, "server_host_key_algo=" + np.server_host_key_algo);

			np.enc_algo_client_to_server = getFirstMatch(client.encryption_algorithms_client_to_server,
					server.encryption_algorithms_client_to_server);
			np.enc_algo_server_to_client = getFirstMatch(client.encryption_algorithms_server_to_client,
					server.encryption_algorithms_server_to_client);

			log.log(30, "enc_algo_client_to_server=" + np.enc_algo_client_to_server);
			log.log(30, "enc_algo_server_to_client=" + np.enc_algo_server_to_client);

			np.mac_algo_client_to_server = getFirstMatch(client.mac_algorithms_client_to_server,
					server.mac_algorithms_client_to_server);
			np.mac_algo_server_to_client = getFirstMatch(client.mac_algorithms_server_to_client,
					server.mac_algorithms_server_to_client);

			log.log(30, "mac_algo_client_to_server=" + np.mac_algo_client_to_server);
			log.log(30, "mac_algo_server_to_client=" + np.mac_algo_server_to_client);

			np.comp_algo_client_to_server = getFirstMatch(client.compression_algorithms_client_to_server,
					server.compression_algorithms_client_to_server);
			np.comp_algo_server_to_client = getFirstMatch(client.compression_algorithms_server_to_client,
					server.compression_algorithms_server_to_client);

			log.log(30, "comp_algo_client_to_server=" + np.comp_algo_client_to_server);
			log.log(30, "comp_algo_server_to_client=" + np.comp_algo_server_to_client);

		

			try {
				np.lang_client_to_server = getFirstMatch(client.languages_client_to_server,
						server.languages_client_to_server);
			} catch (NegotiateException ne1) {
				np.lang_client_to_server = null;
			}

			try {
				np.lang_server_to_client = getFirstMatch(client.languages_server_to_client,
						server.languages_server_to_client);
			} catch (NegotiateException ne2) {
				np.lang_server_to_client = null;
			}
			
		

		if (isGuessOK(client, server))
			np.guessOK = true;

		return np;
	}

	public synchronized void initiateKEX(CryptoWishList cwl, DHGexParameters dhgex) throws IOException
	{
		nextKEXcryptoWishList = cwl;
		filterHostKeyTypes(nextKEXcryptoWishList);
		nextKEXdhgexParameters = dhgex;

		if (kxs == null)
		{
			kxs = new KexState();

			kxs.dhgexParameters = nextKEXdhgexParameters;
			PacketKexInit kp = new PacketKexInit(nextKEXcryptoWishList, rnd);
			kxs.localKEX = kp;
			tm.sendKexMessage(kp.getPayload());
		}
	}

	private boolean establishKeyMaterial()
	{
		try
		{
			int mac_cs_key_len = MessageMac.getKeyLength(kxs.np.mac_algo_client_to_server);
			int enc_cs_key_len = BlockCipherFactory.getKeySize(kxs.np.enc_algo_client_to_server);
			int enc_cs_block_len = BlockCipherFactory.getBlockSize(kxs.np.enc_algo_client_to_server);

			int mac_sc_key_len = MessageMac.getKeyLength(kxs.np.mac_algo_server_to_client);
			int enc_sc_key_len = BlockCipherFactory.getKeySize(kxs.np.enc_algo_server_to_client);
			int enc_sc_block_len = BlockCipherFactory.getBlockSize(kxs.np.enc_algo_server_to_client);

			km = KeyMaterial.create(kxs.getHashAlgorithm(), kxs.H, kxs.K, sessionId, enc_cs_key_len, enc_cs_block_len, mac_cs_key_len,
					enc_sc_key_len, enc_sc_block_len, mac_sc_key_len);
		}
		catch (IllegalArgumentException e)
		{
			return false;
		}
		return true;
	}

	private void finishKex() throws IOException
	{
		if (sessionId == null)
			sessionId = kxs.H;

		establishKeyMaterial();

		/* Tell the other side that we start using the new material */

		PacketNewKeys ign = new PacketNewKeys();
		tm.sendKexMessage(ign.getPayload());

		BlockCipher cbc;
		MessageMac mac;

		try
		{
			cbc = BlockCipherFactory.createCipher(kxs.np.enc_algo_client_to_server, true, km.enc_key_client_to_server,
					km.initial_iv_client_to_server);

			mac = new MessageMac(kxs.np.mac_algo_client_to_server, km.integrity_key_client_to_server);

		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Fatal error during MAC startup!", e);
		}

		tm.changeSendCipher(cbc, mac);
		tm.kexFinished();
	}

	public static String[] getDefaultServerHostkeyAlgorithmList()
	{
		return DEFAULT_KEY_ALGORITHMS.toArray(new String[DEFAULT_KEY_ALGORITHMS.size()]);
	}

	private static List<String> buildDefaultKeyAlgorithms() {
		List<String> algorithms = new ArrayList<>();
		for (KeyAlgorithm<?, ?> algorithm : KeyAlgorithmManager.getSupportedAlgorithms()) {
			algorithms.add(algorithms.size(), algorithm.getKeyFormat());
		}
		return algorithms;
	}

	public static void checkServerHostkeyAlgorithmsList(String[] algos)
	{
		for (String algo : algos) {
			boolean matched = false;
			for (KeyAlgorithm<?, ?> algorithm : KeyAlgorithmManager.getSupportedAlgorithms()) {
				if (algorithm.getKeyFormat().equals(algo)) {
					matched = true;
					break;
				}
			}
			if (!matched) {
				throw new IllegalArgumentException("Unknown server host key algorithm '" + algo + "'");
			}
		}
	}

	/**
	 * If the verifier can indicate which algorithms it knows about for this host, then
	 * filter out our crypto wish list to only include those algorithms. Otherwise we'll
	 * negotiate a host key we have not previously confirmed.
	 *
	 * @param cwl crypto wish list to filter
	 */
	private void filterHostKeyTypes(CryptoWishList cwl) {
		if (verifier instanceof ExtendedServerHostKeyVerifier) {
			ExtendedServerHostKeyVerifier extendedVerifier = (ExtendedServerHostKeyVerifier) verifier;

			List<String> knownAlgorithms = extendedVerifier.getKnownKeyAlgorithmsForHost(hostname, port);
			if (knownAlgorithms != null && knownAlgorithms.size() > 0) {
				ArrayList<String> filteredAlgorithms = new ArrayList<>(knownAlgorithms.size());

				/*
				 * Look at our current wish list and adjust it based on what the client already knows, but
				 * be careful to keep it in the order desired by the wish list.
				 */
				for (String capableAlgo : cwl.serverHostKeyAlgorithms) {
					for (String knownAlgo : knownAlgorithms) {
						if (capableAlgo.equals(knownAlgo)) {
							filteredAlgorithms.add(knownAlgo);
						}
					}
				}

				if (filteredAlgorithms.size() > 0) {
					cwl.serverHostKeyAlgorithms = filteredAlgorithms.toArray(new String[0]);
				}
			}
		}
	}


	public static String[] getDefaultKexAlgorithmList()
	{
		return new String[] { "diffie-hellman-group-exchange-sha256", "diffie-hellman-group-exchange-sha1",
				"diffie-hellman-group14-sha1", "diffie-hellman-group1-sha1","ecdh-sha2-nistp256","ecdh-sha2-nistp384","ecdh-sha2-nistp521","curve25519-sha256","curve25519-sha256@libssh.org" };
	}

	// FIXME this code is not used, the check it makes does not match the implementation in other places.
	public static void checkKexAlgorithmList(String[] algos)
	{
		for (String algo : algos) {
			if ("diffie-hellman-group-exchange-sha1".equals(algo))
				continue;

			if ("diffie-hellman-group14-sha1".equals(algo))
				continue;

			if ("diffie-hellman-group1-sha1".equals(algo))
				continue;

			if ("diffie-hellman-group-exchange-sha256".equals(algo))
				continue;
			if ("ecdh-sha2-nistp256".equals(algo))
				continue;

			if ("ecdh-sha2-nistp384".equals(algo))
				continue;

			if ("ecdh-sha2-nistp521".equals(algo))
				continue;
			if (Curve25519Exchange.NAME.equals(algo)||Curve25519Exchange.ALT_NAME.equals(algo))
				continue;
			throw new IllegalArgumentException("Unknown kex algorithm '" + algo + "'");
		}
	}

	private boolean verifySignature(byte[] sig, byte[] hostkey) throws IOException
	{
		for (KeyAlgorithm<PublicKey, PrivateKey> algorithm : KeyAlgorithmManager.getSupportedAlgorithms()) {
			if (algorithm.getKeyFormat().equals(kxs.np.server_host_key_algo)) {
				PublicKey publicKey = algorithm.decodePublicKey(hostkey);
				byte[] signature = algorithm.decodeSignature(sig);
				return algorithm.verifySignature(kxs.H, signature, publicKey);
			}
		}
		throw new IOException("Unknown server host key algorithm '" + kxs.np.server_host_key_algo + "'");
	}

	public synchronized void handleMessage(byte[] msg, int msglen) throws IOException
	{
		PacketKexInit kip;

		if ((kxs == null) && (msg[0] != Packets.SSH_MSG_KEXINIT))
			throw new IOException("Unexpected KEX message (type " + msg[0] + ")");

		if (ignore_next_kex_packet)
		{
			ignore_next_kex_packet = false;
			return;
		}

		if (msg[0] == Packets.SSH_MSG_KEXINIT)
		{
			if ((kxs != null) && (kxs.state != 0))
				throw new IOException("Unexpected SSH_MSG_KEXINIT message during on-going kex exchange!");

			if (kxs == null)
			{
				/*
				 * Ah, OK, peer wants to do KEX. Let's be nice and play
				 * together.
				 */
				kxs = new KexState();
				kxs.dhgexParameters = nextKEXdhgexParameters;
				kip = new PacketKexInit(nextKEXcryptoWishList, rnd);
				kxs.localKEX = kip;
				tm.sendKexMessage(kip.getPayload());
			}

			kip = new PacketKexInit(msg, 0, msglen);
			kxs.remoteKEX = kip;
			try{
			kxs.np = mergeKexParameters(kxs.localKEX.getKexParameters(), kxs.remoteKEX.getKexParameters());
			}catch(NegotiateException ne){
				throw new IOException("Cannot negotiate algorithms, proposals do not match.", ne);
			}
			

			if (kxs.remoteKEX.isFirst_kex_packet_follows() && !kxs.np.guessOK)
			{
				/*
				 * Guess was wrong, we need to ignore the next kex packet.
				 */

				ignore_next_kex_packet = true;
			}

			if (kxs.np.kex_algo.equals("diffie-hellman-group-exchange-sha1")
					|| kxs.np.kex_algo.equals("diffie-hellman-group-exchange-sha256"))
			{
				if (kxs.dhgexParameters.getMin_group_len() == 0)
				{
					PacketKexDhGexRequestOld dhgexreq = new PacketKexDhGexRequestOld(kxs.dhgexParameters);
					tm.sendKexMessage(dhgexreq.getPayload());

				}
				else
				{
					PacketKexDhGexRequest dhgexreq = new PacketKexDhGexRequest(kxs.dhgexParameters);
					tm.sendKexMessage(dhgexreq.getPayload());
				}
				kxs.state = 1;

				if (kxs.np.kex_algo.endsWith("sha1")) {
					kxs.setHashAlgorithm("SHA1");
				} else {
					kxs.setHashAlgorithm("SHA-256");
				}

				return;
			}

			if (kxs.np.kex_algo.equals("diffie-hellman-group1-sha1")
					|| kxs.np.kex_algo.equals(Curve25519Exchange.NAME)
					|| kxs.np.kex_algo.equals(Curve25519Exchange.ALT_NAME)
					|| kxs.np.kex_algo.equals("diffie-hellman-group14-sha1")
					|| kxs.np.kex_algo.equals("ecdh-sha2-nistp521")
					|| kxs.np.kex_algo.equals("ecdh-sha2-nistp384")
					|| kxs.np.kex_algo.equals("ecdh-sha2-nistp256"))
			{


				kxs.dhx = GenericDhExchange.getInstance(kxs.np.kex_algo);
				kxs.dhx.init(kxs.np.kex_algo);
				kxs.setHashAlgorithm(kxs.dhx.getHashAlgo());
				PacketKexDHInit kp = new PacketKexDHInit(kxs.dhx.getE());
				tm.sendKexMessage(kp.getPayload());
				kxs.state = 1;

				return;
			}

			throw new IllegalStateException("Unkown KEX method!");
		}

		if (msg[0] == Packets.SSH_MSG_NEWKEYS)
		{
			if (km == null)
				throw new IOException("Peer sent SSH_MSG_NEWKEYS, but I have no key material ready!");

			BlockCipher cbc;
			MessageMac mac;

			try
			{
				cbc = BlockCipherFactory.createCipher(kxs.np.enc_algo_server_to_client, false,
						km.enc_key_server_to_client, km.initial_iv_server_to_client);

				mac = new MessageMac(kxs.np.mac_algo_server_to_client, km.integrity_key_server_to_client);

			}
			catch (IllegalArgumentException e1)
			{
				throw new IOException("Fatal error during MAC startup!");
			}

			tm.changeRecvCipher(cbc, mac);

			ConnectionInfo sci = new ConnectionInfo();

			kexCount++;

			sci.keyExchangeAlgorithm = kxs.np.kex_algo;
			sci.keyExchangeCounter = kexCount;
			sci.clientToServerCryptoAlgorithm = kxs.np.enc_algo_client_to_server;
			sci.serverToClientCryptoAlgorithm = kxs.np.enc_algo_server_to_client;
			sci.clientToServerMACAlgorithm = kxs.np.mac_algo_client_to_server;
			sci.serverToClientMACAlgorithm = kxs.np.mac_algo_server_to_client;
			sci.serverHostKeyAlgorithm = kxs.np.server_host_key_algo;
			sci.serverHostKey = kxs.hostkey;

			synchronized (accessLock)
			{
				lastConnInfo = sci;
				accessLock.notifyAll();
			}

			kxs = null;
			return;
		}

		if ((kxs == null) || (kxs.state == 0))
			throw new IOException("Unexpected Kex submessage!");

		if (kxs.np.kex_algo.equals("diffie-hellman-group-exchange-sha1")
				|| kxs.np.kex_algo.equals("diffie-hellman-group-exchange-sha256"))
		{
			if (kxs.state == 1)
			{
				PacketKexDhGexGroup dhgexgrp = new PacketKexDhGexGroup(msg, 0, msglen);
				kxs.dhgx = new DhGroupExchange(dhgexgrp.getP(), dhgexgrp.getG());
				kxs.dhgx.init(rnd);
				PacketKexDhGexInit dhgexinit = new PacketKexDhGexInit(kxs.dhgx.getE());
				tm.sendKexMessage(dhgexinit.getPayload());
				kxs.state = 2;
				return;
			}

			if (kxs.state == 2)
			{
				PacketKexDhGexReply dhgexrpl = new PacketKexDhGexReply(msg, 0, msglen);

				kxs.hostkey = dhgexrpl.getHostKey();

				if (verifier != null)
				{
					boolean vres = false;

					try
					{
						vres = verifier.verifyServerHostKey(hostname, port, kxs.np.server_host_key_algo, kxs.hostkey);
					}
					catch (Exception e)
					{
						throw new IOException(
								"The server hostkey was not accepted by the verifier callback.", e);
					}

					if (!vres)
						throw new IOException("The server hostkey was not accepted by the verifier callback");
				}

				kxs.dhgx.setF(dhgexrpl.getF());

				try
				{
					kxs.H = kxs.dhgx.calculateH(kxs.getHashAlgorithm(),csh.getClientString(), csh.getServerString(),
							kxs.localKEX.getPayload(), kxs.remoteKEX.getPayload(), dhgexrpl.getHostKey(),
							kxs.dhgexParameters);
				}
				catch (IllegalArgumentException e)
				{
					throw new IOException("KEX error.", e);
				}

				boolean res = verifySignature(dhgexrpl.getSignature(), kxs.hostkey);

				if (!res)
					throw new IOException("Hostkey signature sent by remote is wrong!");

				kxs.K = kxs.dhgx.getK();

				finishKex();
				kxs.state = -1;
				return;
			}

			throw new IllegalStateException("Illegal State in KEX Exchange!");
		}

		if (kxs.np.kex_algo.equals("diffie-hellman-group1-sha1")
				|| kxs.np.kex_algo.equals("diffie-hellman-group14-sha1")
				|| kxs.np.kex_algo.equals("ecdh-sha2-nistp256")
				|| kxs.np.kex_algo.equals("ecdh-sha2-nistp384")
				|| kxs.np.kex_algo.equals("ecdh-sha2-nistp521")
				|| kxs.np.kex_algo.equals(Curve25519Exchange.NAME)
				|| kxs.np.kex_algo.equals(Curve25519Exchange.ALT_NAME))
		{
			if (kxs.state == 1)
			{
				PacketKexDHReply dhr = new PacketKexDHReply(msg, 0, msglen);

				kxs.hostkey = dhr.getHostKey();

				if (verifier != null)
				{
					boolean vres = false;

					try
					{
						vres = verifier.verifyServerHostKey(hostname, port, kxs.np.server_host_key_algo, kxs.hostkey);
					}
					catch (Exception e)
					{
						throw new IOException(
								"The server hostkey was not accepted by the verifier callback.", e);
					}

					if (!vres)
						throw new IOException("The server hostkey was not accepted by the verifier callback");
				}

				kxs.dhx.setF(dhr.getF());

				try
				{
					kxs.H = kxs.dhx.calculateH(csh.getClientString(), csh.getServerString(), kxs.localKEX.getPayload(),
							kxs.remoteKEX.getPayload(), dhr.getHostKey());
				}
				catch (IllegalArgumentException e)
				{
					throw new IOException("KEX error.", e);
				}

				boolean res = verifySignature(dhr.getSignature(), kxs.hostkey);

				if (!res)
					throw new IOException("Hostkey signature sent by remote is wrong!");

				kxs.K = kxs.dhx.getK();

				finishKex();
				kxs.state = -1;
				return;
			}
		}

		throw new IllegalStateException("Unkown KEX method! (" + kxs.np.kex_algo + ")");
	}

	public void handleEndMessage(Throwable cause) throws IOException {
		synchronized (accessLock) {
			connectionClosed = true;
			accessLock.notifyAll();
		}
	}
}
