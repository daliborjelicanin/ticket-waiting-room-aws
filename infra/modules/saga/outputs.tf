output "connection_arn" {
  description = "The connection the saga's HTTP tasks name (ConnectionArn in the ASL)."
  value       = aws_cloudwatch_event_connection.saga.arn
}

output "connection_name" {
  description = "The connection's name, <prefix>-saga."
  value       = aws_cloudwatch_event_connection.saga.name
}

output "connection_secret_arn" {
  description = "The events!connection/... secret EventBridge keeps the authorization in (AWS-owned key, D11)."
  value       = aws_cloudwatch_event_connection.saga.secret_arn
}

output "state_machine_arn" {
  description = "The checkout saga, as foundation's purchase integration starts it."
  value       = aws_sfn_state_machine.saga.arn
}
