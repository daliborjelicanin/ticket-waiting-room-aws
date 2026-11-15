variable "name_prefix" {
  description = "Name prefix, twr-<env> (D25)."
  type        = string
}

variable "kms_key_arn" {
  description = "The CMK: purchase-confirmed carries the buyer's email (D11). Queues and topics use it."
  type        = string
}

variable "queues" {
  description = <<-EOT
    Queues by short name; each gets a DLQ. The visibility timeout is derived from the consumer,
    never typed (retro §3):
      lambda: 6 x the function timeout (AWS's rule for event source mappings)
      pump:   batch_size x message_seconds x 3 (SqsMessagePump walks a batch on one thread,
              and the visibility clock starts at receive for the whole batch)
  EOT
  type = map(object({
    consumer          = string           # lambda | pump
    function_timeout  = optional(number) # lambda: seconds
    batch_size        = optional(number) # pump: messages per receive
    message_seconds   = optional(number) # pump: worst-case seconds per message
    max_receive_count = number
  }))

  validation {
    condition     = alltrue([for q in values(var.queues) : contains(["lambda", "pump"], q.consumer)])
    error_message = "consumer is lambda or pump."
  }

  validation {
    condition = alltrue([for q in values(var.queues) : (
      q.consumer == "lambda"
      ? q.function_timeout != null
      : (q.batch_size != null && q.message_seconds != null)
    )])
    error_message = "A lambda consumer needs function_timeout; a pump consumer needs batch_size and message_seconds."
  }

  validation {
    condition     = alltrue([for q in values(var.queues) : (q.max_receive_count >= 1 && q.max_receive_count <= 1000)])
    error_message = "max_receive_count is 1 to 1000."
  }
}

variable "topics" {
  description = "SNS topics by short name, with the queues (keys of var.queues) subscribed to each."
  type = map(object({
    queue_subscribers = list(string)
  }))
  default = {}
}

variable "message_retention_seconds" {
  description = "Main queues: 4 days, the SQS default, pinned."
  type        = number
  default     = 345600
}

variable "dlq_retention_seconds" {
  description = "DLQs: 14 days, the maximum. A DLQ is read by a human, days later."
  type        = number
  default     = 1209600
}
