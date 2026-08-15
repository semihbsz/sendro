//! Ephemeral text messages — PROTOCOL.md §11.
//!
//! Messages are a clipboard hop, not a mailbox. Everything in this module
//! lives in RAM only: nothing is written to disk, nothing enters history,
//! and message *contents* are never logged (only ids and byte counts).
//!
//! * host → client: [`Core::send_message`] pushes into the target device's
//!   in-memory inbox and wakes its outbox long-poll. The inbox is drained
//!   the moment it is written into an outbox response (at-most-once).
//! * client → host: `POST /api/v1/messages` (server.rs) calls
//!   [`Core::receive_message`], which emits [`CoreEvent::MessageReceived`]
//!   and keeps the message in [`Core::incoming_messages`] until dismissed.

use uuid::Uuid;

use crate::types::{now_ms, IncomingMessage, Message};
use crate::{Core, CoreEvent};

/// Maximum UTF-8 length of `text`, in bytes (§11).
pub const MAX_MESSAGE_BYTES: usize = 32 * 1024;

/// Maximum undelivered messages held per device inbox; pushing past this
/// drops the oldest (§11).
pub const MAX_INBOX: usize = 20;

/// Maximum incoming (client → host) messages held for the UI.
pub const MAX_INCOMING: usize = 20;

impl Core {
    /// Queue an ephemeral text message for a paired device (§11.1).
    ///
    /// Validates the 32 KiB UTF-8 limit, pushes onto that device's in-memory
    /// inbox (evicting the oldest past [`MAX_INBOX`]) and wakes the device's
    /// outbox long-poll so delivery is immediate.
    pub fn send_message(&self, device_id: Uuid, text: String) -> anyhow::Result<()> {
        if text.is_empty() {
            anyhow::bail!("message must not be empty");
        }
        if text.len() > MAX_MESSAGE_BYTES {
            anyhow::bail!(
                "message too long: {} bytes (max {MAX_MESSAGE_BYTES})",
                text.len()
            );
        }
        if self.trusted_device(device_id).is_none() {
            anyhow::bail!("device {device_id} is not paired");
        }

        let message = Message {
            message_id: Uuid::new_v4(),
            text,
            sent_at_ms: now_ms(),
            sender_name: self.settings.read().device_name.clone(),
        };
        let message_id = message.message_id;
        let bytes = message.text.len();
        {
            let mut inboxes = self.message_inbox.write();
            let inbox = inboxes.entry(device_id).or_default();
            while inbox.len() >= MAX_INBOX {
                inbox.pop_front();
            }
            inbox.push_back(message);
        }
        // Same wake path as a freshly published offer.
        self.notify_handle(device_id).notify_waiters();
        // Contents are never logged.
        tracing::debug!("queued message {message_id} ({bytes} bytes) for {device_id}");
        Ok(())
    }

    /// Drain the device's inbox for an outbox response. Delivery is
    /// at-most-once: the messages are gone from the host the instant they
    /// are handed to the response builder.
    pub(crate) fn drain_messages_for(&self, device_id: Uuid) -> Vec<Message> {
        let mut inboxes = self.message_inbox.write();
        match inboxes.get_mut(&device_id) {
            Some(inbox) if !inbox.is_empty() => inbox.drain(..).collect(),
            _ => Vec::new(),
        }
    }

    /// Number of messages waiting for a device (tests / diagnostics).
    pub fn pending_message_count(&self, device_id: Uuid) -> usize {
        self.message_inbox
            .read()
            .get(&device_id)
            .map_or(0, |inbox| inbox.len())
    }

    /// Accept a message from a paired device (§11.2): hold it in memory for
    /// the UI and emit [`CoreEvent::MessageReceived`].
    pub(crate) fn receive_message(&self, sender_name: String, text: String) -> IncomingMessage {
        let incoming = IncomingMessage {
            message_id: Uuid::new_v4(),
            text,
            sender_name,
            received_at_ms: now_ms(),
        };
        {
            let mut list = self.incoming.write();
            while list.len() >= MAX_INCOMING {
                list.pop_front();
            }
            list.push_back(incoming.clone());
        }
        self.emit(CoreEvent::MessageReceived {
            message_id: incoming.message_id,
            text: incoming.text.clone(),
            sender_name: incoming.sender_name.clone(),
            received_at_ms: incoming.received_at_ms,
        });
        incoming
    }

    /// Messages received from paired devices and not yet dismissed, newest
    /// last. In-memory only.
    pub fn incoming_messages(&self) -> Vec<IncomingMessage> {
        self.incoming.read().iter().cloned().collect()
    }

    /// Discard one received message forever. Returns true if it existed.
    pub fn dismiss_message(&self, message_id: Uuid) -> bool {
        let mut list = self.incoming.write();
        let before = list.len();
        list.retain(|m| m.message_id != message_id);
        list.len() != before
    }

    /// Discard every received message.
    pub fn clear_messages(&self) {
        self.incoming.write().clear();
    }
}
