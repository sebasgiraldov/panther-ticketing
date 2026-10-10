locals {
  # Suffix tables with environment to allow dev and prod in the same account
  prefix = "panther-${var.environment}"
}

# ── Events ─────────────────────────────────────────────────────────────────────
resource "aws_dynamodb_table" "events" {
  name         = "${local.prefix}-events"
  billing_mode = "PAY_PER_REQUEST" # on-demand — no capacity planning needed
  hash_key     = "id"

  attribute {
    name = "id"
    type = "S"
  }

  point_in_time_recovery { enabled = true } # free PITR for accidental deletes

  tags = { Name = "${local.prefix}-events" }
}

# ── Ticket Types ───────────────────────────────────────────────────────────────
resource "aws_dynamodb_table" "ticket_types" {
  name         = "${local.prefix}-ticket-types"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"

  attribute {
    name = "id"
    type = "S"
  }

  attribute {
    name = "eventId"
    type = "S"
  }

  # GSI to query all ticket types for a given event
  global_secondary_index {
    name            = "eventId-index"
    hash_key        = "eventId"
    projection_type = "ALL"
  }

  point_in_time_recovery { enabled = true }
  tags = { Name = "${local.prefix}-ticket-types" }
}

# ── Tickets ────────────────────────────────────────────────────────────────────
resource "aws_dynamodb_table" "tickets" {
  name         = "${local.prefix}-tickets"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"

  attribute {
    name = "id"
    type = "S"
  }

  attribute {
    name = "eventId"
    type = "S"
  }

  attribute {
    name = "ticketTypeId"
    type = "S"
  }

  attribute {
    name = "reservationId"
    type = "S"
  }

  # GSI for ReserveTicketsUseCase: find AVAILABLE tickets by event+type
  global_secondary_index {
    name            = "eventId-ticketTypeId-status-index"
    hash_key        = "eventId"
    range_key       = "ticketTypeId"
    projection_type = "ALL"
  }

  # GSI for ProcessOrderUseCase / ReleaseExpiredReservations: find tickets by reservation
  global_secondary_index {
    name            = "reservationId-index"
    hash_key        = "reservationId"
    projection_type = "ALL"
  }

  point_in_time_recovery { enabled = true }
  tags = { Name = "${local.prefix}-tickets" }
}

# ── Reservations ───────────────────────────────────────────────────────────────
resource "aws_dynamodb_table" "reservations" {
  name         = "${local.prefix}-reservations"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"

  attribute {
    name = "id"
    type = "S"
  }

  point_in_time_recovery { enabled = true }
  tags = { Name = "${local.prefix}-reservations" }
}

# ── Orders ─────────────────────────────────────────────────────────────────────
resource "aws_dynamodb_table" "orders" {
  name         = "${local.prefix}-orders"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"

  attribute {
    name = "id"
    type = "S"
  }

  attribute {
    name = "idempotencyKey"
    type = "S"
  }

  # GSI for idempotency check in PurchaseTicketsUseCase
  global_secondary_index {
    name            = "idempotencyKey-index"
    hash_key        = "idempotencyKey"
    projection_type = "ALL"
  }

  point_in_time_recovery { enabled = true }
  tags = { Name = "${local.prefix}-orders" }
}

# ── Inventory Counters ─────────────────────────────────────────────────────────
# Composite PK: eventId#ticketTypeId — O(1) reads for availability checks (req 4.7)
resource "aws_dynamodb_table" "inventory" {
  name         = "${local.prefix}-inventory"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"

  attribute {
    name = "pk"
    type = "S"
  }

  point_in_time_recovery { enabled = true }
  tags = { Name = "${local.prefix}-inventory" }
}

# ── Audit Log ──────────────────────────────────────────────────────────────────
# Append-only table. TTL set to 90 days to control storage costs.
resource "aws_dynamodb_table" "audit" {
  name         = "${local.prefix}-audit"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"

  attribute {
    name = "id"
    type = "S"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  point_in_time_recovery { enabled = true }
  tags = { Name = "${local.prefix}-audit" }
}
