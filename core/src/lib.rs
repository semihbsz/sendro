//! sendro-core — the engine behind Sendro, a private LAN file-transfer app
//! (Windows PC ⇄ iPhone). No cloud, no internet requirement at runtime.
//!
//! Implements PROTOCOL.md (wire protocol v1) and CORE_API.md (public seam
//! consumed by the Tauri desktop shell).

pub mod auth;
pub mod config;
pub mod discovery;
pub mod events;
pub mod filename;
pub mod hashing;
pub mod history;
pub mod link;
pub mod messages;
pub mod net;
pub mod pairing;
pub mod qr;
pub mod range;
pub mod server;
pub mod state;
pub mod transfers;
pub mod types;
pub mod watch;

use std::collections::{HashMap, VecDeque};
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Weak};
use std::time::Duration;

use anyhow::Context;
use parking_lot::{Mutex, RwLock};
use tokio::sync::{broadcast, watch as tokio_watch, Notify};
use tokio::task::JoinHandle;
use uuid::Uuid;

pub use config::CoreConfig;
pub use events::CoreEvent;
pub use link::{LinkFile, LinkOptions, LinkSession};
pub use net::NetIface;
pub use qr::{QrPairing, QrUrl};
pub use types::{
    DiscoveredHost, HistoryEntry, HostInfo, IncomingMessage, Message, Settings, TransferState,
    TransferSummary, TrustedDevice, WatchFolderConfig,
};

use state::{Identity, StoredDevice};
use transfers::TransferRecord;
use types::{now_ms, PLATFORM, PROTOCOL_VERSION};
use watch::PendingDetection;

/// Arc-shared engine. Construct with [`Core::start`].
pub struct Core {
    pub(crate) device_id: Uuid,
    pub(crate) data_dir: PathBuf,
    pub(crate) settings: RwLock<Settings>,
    pub(crate) port: u16,

    pub(crate) events: broadcast::Sender<CoreEvent>,
    pub(crate) pairings: pairing::PairingManager,
    pub(crate) trusted: RwLock<Vec<StoredDevice>>,

    pub(crate) transfers: RwLock<HashMap<Uuid, TransferRecord>>,
    pub(crate) seq: AtomicU64,
    pub(crate) outbox_notify: Mutex<HashMap<Uuid, Arc<Notify>>>,
    pub(crate) paused: AtomicBool,
    /// transfer_id → number of currently open download streams for it.
    pub(crate) active_streams: Mutex<HashMap<Uuid, usize>>,

    /// §11 ephemeral text, host → client. Per-device in-memory inbox, capped
    /// at [`messages::MAX_INBOX`], drained on outbox read. NEVER persisted.
    pub(crate) message_inbox: RwLock<HashMap<Uuid, VecDeque<Message>>>,
    /// §11.2 ephemeral text, client → host. Held only until the user
    /// dismisses the card. NEVER persisted, never in history.
    pub(crate) incoming: RwLock<VecDeque<IncomingMessage>>,

    /// §14 Sendro Link guest session. RAM only — deliberately not part of
    /// anything written to `data_dir`, so it cannot survive a restart.
    pub(crate) link: RwLock<Option<link::LinkState>>,
    /// Tokens of stopped/expired sessions, kept so their routes answer
    /// `410 gone` instead of `404`, and so they are never reused.
    pub(crate) link_retired: Mutex<VecDeque<String>>,
    /// Live guest connections, capped at [`link::MAX_GUEST_CONNECTIONS`].
    pub(crate) link_conns: Arc<AtomicUsize>,

    pub(crate) history: RwLock<Vec<HistoryEntry>>,
    pub(crate) watch_folders: RwLock<Vec<WatchFolderConfig>>,
    pub(crate) pending_detections: Mutex<HashMap<Uuid, PendingDetection>>,
    pub(crate) watch_poll: Duration,
    pub(crate) watch_stable_polls: u32,

    /// Serializes exists-check + rename/create for collision-safe naming.
    pub(crate) fs_lock: Mutex<()>,

    pub(crate) shutdown_tx: tokio_watch::Sender<bool>,
    pub(crate) tasks: Mutex<Vec<JoinHandle<()>>>,
    pub(crate) mdns: Mutex<Option<mdns_sd::ServiceDaemon>>,
    pub(crate) mdns_enabled: bool,
    /// Addresses the mDNS record was last built for; a change (hotspot
    /// adapter appearing after startup) triggers a re-registration.
    pub(crate) advertised_ips: Mutex<Vec<String>>,
    /// Weak self reference so `&self` methods can spawn owning tasks.
    pub(crate) self_ref: RwLock<Option<Weak<Core>>>,
}

impl Core {
    /// Boot the engine: load persisted state, bind the HTTP server
    /// (preferred_port, falling back +1..+20), start mDNS advertisement,
    /// the watch-folder stabilizer and the maintenance sweep.
    pub async fn start(cfg: CoreConfig) -> anyhow::Result<Arc<Core>> {
        std::fs::create_dir_all(&cfg.data_dir)
            .with_context(|| format!("create data dir {}", cfg.data_dir.display()))?;
        std::fs::create_dir_all(&cfg.receive_dir)
            .with_context(|| format!("create receive dir {}", cfg.receive_dir.display()))?;

        // Stable host identity across restarts.
        let identity_path = cfg.data_dir.join(state::IDENTITY_FILE);
        let identity: Identity = match state::load_json(&identity_path) {
            Some(id) => id,
            None => {
                let id = Identity {
                    device_id: Uuid::new_v4(),
                };
                state::atomic_write_json(&identity_path, &id)?;
                id
            }
        };

        // Persisted settings win over the passed config (they represent the
        // user's saved choices); first run seeds them from the config.
        let settings_path = cfg.data_dir.join(state::SETTINGS_FILE);
        let settings: Settings = match state::load_json(&settings_path) {
            Some(s) => s,
            None => {
                let s = Settings {
                    device_name: cfg.device_name.clone(),
                    receive_dir: cfg.receive_dir.to_string_lossy().into_owned(),
                    preferred_port: cfg.preferred_port,
                    concurrency: cfg.concurrency.max(1),
                    launch_on_startup: false,
                    minimize_to_tray: true,
                };
                state::atomic_write_json(&settings_path, &s)?;
                s
            }
        };

        let trusted: Vec<StoredDevice> =
            state::load_json(&cfg.data_dir.join(state::TRUSTED_DEVICES_FILE)).unwrap_or_default();
        let history: Vec<HistoryEntry> =
            state::load_json(&cfg.data_dir.join(state::HISTORY_FILE)).unwrap_or_default();
        let watch_folders: Vec<WatchFolderConfig> =
            state::load_json(&cfg.data_dir.join(state::WATCH_FOLDERS_FILE)).unwrap_or_default();

        // Bind the listener before constructing Core so the real port is known.
        let listener = bind_listener(settings.preferred_port).await?;
        let port = listener.local_addr()?.port();

        let (shutdown_tx, _) = tokio_watch::channel(false);
        let (events, _) = broadcast::channel(events::EVENT_CHANNEL_CAPACITY);

        let core = Arc::new(Core {
            device_id: identity.device_id,
            data_dir: cfg.data_dir.clone(),
            settings: RwLock::new(settings),
            port,
            events,
            pairings: pairing::PairingManager::new(),
            trusted: RwLock::new(trusted),
            transfers: RwLock::new(HashMap::new()),
            seq: AtomicU64::new(1),
            outbox_notify: Mutex::new(HashMap::new()),
            paused: AtomicBool::new(false),
            active_streams: Mutex::new(HashMap::new()),
            message_inbox: RwLock::new(HashMap::new()),
            incoming: RwLock::new(VecDeque::new()),
            link: RwLock::new(None),
            link_retired: Mutex::new(link::new_retired_store()),
            link_conns: link::new_conn_counter(),
            history: RwLock::new(history),
            watch_folders: RwLock::new(watch_folders),
            pending_detections: Mutex::new(HashMap::new()),
            watch_poll: Duration::from_millis(cfg.watch_poll_ms.max(10)),
            watch_stable_polls: cfg.watch_stable_polls.max(1),
            fs_lock: Mutex::new(()),
            shutdown_tx,
            tasks: Mutex::new(Vec::new()),
            mdns: Mutex::new(None),
            mdns_enabled: cfg.mdns_enabled,
            advertised_ips: Mutex::new(Vec::new()),
            self_ref: RwLock::new(None),
        });
        *core.self_ref.write() = Some(Arc::downgrade(&core));

        // Restore interrupted/pending outgoing offers from a previous run.
        core.restore_offers();

        // HTTP server.
        let router = server::build_router(core.clone());
        let mut shutdown_rx = core.shutdown_tx.subscribe();
        let server_task = tokio::spawn(async move {
            let result = axum::serve(listener, router)
                .with_graceful_shutdown(async move {
                    let _ = shutdown_rx.wait_for(|v| *v).await;
                })
                .await;
            if let Err(e) = result {
                tracing::error!("http server error: {e}");
            }
        });

        // Watch-folder stabilizer.
        let watch_task = tokio::spawn(watch::watch_loop(
            core.clone(),
            core.shutdown_tx.subscribe(),
        ));
        // Maintenance sweep: offer expiry (24 h) + stale pairing sessions.
        let sweep_task = tokio::spawn(maintenance_loop(
            core.clone(),
            core.shutdown_tx.subscribe(),
        ));
        {
            let mut tasks = core.tasks.lock();
            tasks.push(server_task);
            tasks.push(watch_task);
            tasks.push(sweep_task);
        }

        // mDNS advertisement (best effort — a broken multicast stack must
        // not prevent manual ip:port connections from working).
        if core.mdns_enabled {
            match discovery::advertise(&core) {
                Ok(daemon) => *core.mdns.lock() = Some(daemon),
                Err(e) => tracing::warn!("mDNS advertisement unavailable: {e}"),
            }
        }

        core.emit(CoreEvent::ServerStarted { port });
        tracing::info!(
            "sendro-core started: deviceId={} port={port}",
            identity.device_id
        );
        Ok(core)
    }

    /// Graceful shutdown: stop the HTTP server, background tasks and mDNS,
    /// then flush persistent state.
    pub async fn shutdown(self: &Arc<Core>) {
        let _ = self.shutdown_tx.send(true);
        let tasks: Vec<JoinHandle<()>> = std::mem::take(&mut *self.tasks.lock());
        for task in tasks {
            if tokio::time::timeout(Duration::from_secs(5), task)
                .await
                .is_err()
            {
                tracing::warn!("background task did not stop in time");
            }
        }
        if let Some(daemon) = self.mdns.lock().take() {
            let _ = daemon.shutdown();
        }
        self.save_trusted();
        self.save_history();
        self.save_offers();
    }

    pub fn info(&self) -> HostInfo {
        HostInfo {
            device_id: self.device_id,
            device_name: self.settings.read().device_name.clone(),
            platform: PLATFORM.to_string(),
            api_port: self.port,
            local_ips: net::info_local_ips(),
            protocol_version: PROTOCOL_VERSION,
        }
    }

    pub fn subscribe(&self) -> broadcast::Receiver<CoreEvent> {
        self.events.subscribe()
    }

    // -- pairing / trust ---------------------------------------------------

    pub fn trusted_devices(&self) -> Vec<TrustedDevice> {
        self.trusted.read().iter().map(|d| d.public()).collect()
    }

    pub fn revoke_device(&self, device_id: Uuid) -> bool {
        let removed = {
            let mut trusted = self.trusted.write();
            let before = trusted.len();
            trusted.retain(|d| d.device_id != device_id);
            trusted.len() != before
        };
        if removed {
            self.save_trusted();
        }
        removed
    }

    // -- settings ----------------------------------------------------------

    pub fn settings(&self) -> Settings {
        self.settings.read().clone()
    }

    /// Update settings. `receive_dir` and `concurrency` apply immediately;
    /// `device_name` applies to subsequent offers/info; `preferred_port`
    /// takes effect on next start (the server keeps its bound port).
    pub fn update_settings(&self, mut s: Settings) -> anyhow::Result<()> {
        s.concurrency = s.concurrency.max(1);
        if s.device_name.trim().is_empty() {
            anyhow::bail!("device_name must not be empty");
        }
        std::fs::create_dir_all(&s.receive_dir)
            .with_context(|| format!("create receive dir {}", s.receive_dir))?;
        state::atomic_write_json(&self.data_dir.join(state::SETTINGS_FILE), &s)?;
        *self.settings.write() = s;
        Ok(())
    }

    /// Global pause gate: while paused, the outbox withholds offers and the
    /// download endpoint answers `503` + `Retry-After` for new chunk
    /// requests (already-open streams finish their current response).
    pub fn pause_transfers(&self, paused: bool) {
        self.paused.store(paused, Ordering::SeqCst);
        if !paused {
            // Wake all long-pollers so held offers are delivered promptly.
            for notify in self.outbox_notify.lock().values() {
                notify.notify_waiters();
            }
        }
    }

    pub fn is_paused(&self) -> bool {
        self.paused.load(Ordering::SeqCst)
    }

    // -- discovery (future desktop features) --------------------------------

    /// Browse the LAN for other Sendro instances for `timeout`.
    pub async fn discover(&self, timeout: Duration) -> Vec<DiscoveredHost> {
        match discovery::browse(timeout, Some(self.device_id)).await {
            Ok(hosts) => hosts,
            Err(e) => {
                tracing::warn!("mDNS browse failed: {e}");
                Vec::new()
            }
        }
    }

    // -- internals ----------------------------------------------------------

    pub(crate) fn emit(&self, event: CoreEvent) {
        // Nobody listening is fine (e.g. before the shell subscribes).
        let _ = self.events.send(event);
    }

    pub(crate) fn device_by_token_hash(&self, hash: &str) -> Option<TrustedDevice> {
        self.trusted
            .read()
            .iter()
            .find(|d| d.token_sha256 == hash)
            .map(|d| d.public())
    }

    pub(crate) fn trusted_device(&self, device_id: Uuid) -> Option<TrustedDevice> {
        self.trusted
            .read()
            .iter()
            .find(|d| d.device_id == device_id)
            .map(|d| d.public())
    }

    /// Update last-seen for a device; persisted at most once a minute to
    /// avoid rewriting trusted_devices.json on every request.
    pub(crate) fn touch_device(&self, device_id: Uuid) {
        let now = now_ms();
        let mut persist = false;
        {
            let mut trusted = self.trusted.write();
            if let Some(d) = trusted.iter_mut().find(|d| d.device_id == device_id) {
                persist = d.last_seen_ms.map_or(true, |prev| now - prev >= 60_000);
                d.last_seen_ms = Some(now);
            }
        }
        if persist {
            self.save_trusted();
        }
    }

    pub(crate) fn add_trusted_device(&self, device: StoredDevice) {
        {
            let mut trusted = self.trusted.write();
            // Re-pairing an existing device replaces its token.
            trusted.retain(|d| d.device_id != device.device_id);
            trusted.push(device);
        }
        self.save_trusted();
    }

    pub(crate) fn save_trusted(&self) {
        let snapshot = self.trusted.read().clone();
        if let Err(e) = state::atomic_write_json(
            &self.data_dir.join(state::TRUSTED_DEVICES_FILE),
            &snapshot,
        ) {
            tracing::error!("failed to persist trusted devices: {e}");
        }
    }

    pub(crate) fn save_watch_folders(&self) {
        let snapshot = self.watch_folders.read().clone();
        if let Err(e) = state::atomic_write_json(
            &self.data_dir.join(state::WATCH_FOLDERS_FILE),
            &snapshot,
        ) {
            tracing::error!("failed to persist watch folders: {e}");
        }
    }

    pub(crate) fn notify_handle(&self, device_id: Uuid) -> Arc<Notify> {
        self.outbox_notify
            .lock()
            .entry(device_id)
            .or_insert_with(|| Arc::new(Notify::new()))
            .clone()
    }

    pub(crate) fn notify_device(&self, device_id: Uuid) {
        if let Some(n) = self.outbox_notify.lock().get(&device_id) {
            n.notify_waiters();
        }
    }

    pub(crate) fn receive_dir(&self) -> PathBuf {
        PathBuf::from(self.settings.read().receive_dir.clone())
    }

    pub(crate) fn concurrency(&self) -> usize {
        self.settings.read().concurrency.max(1)
    }

    /// Re-register the mDNS service when the set of local addresses changed.
    ///
    /// The daemon is created with `enable_addr_auto()`, so it tracks
    /// interface changes by itself; this is the belt-and-braces path for the
    /// case this whole feature exists for — a Windows "Mobile hotspot"
    /// adapter that only appears once the user turns it on, long after
    /// startup. Called from `network_interfaces()` (and therefore from
    /// `start_qr_pairing()`), never from a request handler.
    pub(crate) fn refresh_advertisement(&self, ifaces: &[net::NetIface]) {
        if !self.mdns_enabled {
            return;
        }
        let addresses: Vec<String> = ifaces.iter().map(|i| i.address.clone()).collect();
        {
            let mut advertised = self.advertised_ips.lock();
            if *advertised == addresses {
                return;
            }
            *advertised = addresses;
        }
        let daemon = self.mdns.lock();
        if let Some(daemon) = daemon.as_ref() {
            if let Err(e) = discovery::advertise_on(self, daemon) {
                tracing::warn!("mDNS re-registration failed: {e}");
            } else {
                tracing::info!("mDNS: re-advertised after an interface change");
            }
        }
    }
}

/// Bind `preferred`; on conflict fall back to preferred+1..=preferred+20.
/// `preferred == 0` binds an OS-assigned ephemeral port (tests).
async fn bind_listener(preferred: u16) -> anyhow::Result<tokio::net::TcpListener> {
    if preferred == 0 {
        return tokio::net::TcpListener::bind(("0.0.0.0", 0))
            .await
            .context("bind ephemeral port");
    }
    let mut last_err: Option<std::io::Error> = None;
    for offset in 0..=20u16 {
        let Some(port) = preferred.checked_add(offset) else {
            break;
        };
        match tokio::net::TcpListener::bind(SocketAddr::from(([0, 0, 0, 0], port))).await {
            Ok(l) => return Ok(l),
            Err(e) => last_err = Some(e),
        }
    }
    Err(anyhow::anyhow!(
        "no free port in {}..={}: {}",
        preferred,
        preferred.saturating_add(20),
        last_err.map(|e| e.to_string()).unwrap_or_default()
    ))
}

/// Periodic maintenance: expire offers older than 24 h, purge stale pairing
/// sessions, and emit `PairingFailed` for sessions that timed out.
async fn maintenance_loop(core: Arc<Core>, mut shutdown: tokio_watch::Receiver<bool>) {
    let mut tick = tokio::time::interval(Duration::from_secs(30));
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        tokio::select! {
            _ = tick.tick() => {}
            _ = shutdown.wait_for(|v| *v) => break,
        }
        core.expire_stale_offers();
        core.reap_expired_link();
        for pairing_id in core.pairings.purge_expired() {
            core.emit(CoreEvent::PairingFailed { pairing_id });
        }
    }
}
