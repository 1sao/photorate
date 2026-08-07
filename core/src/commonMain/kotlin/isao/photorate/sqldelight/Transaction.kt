package isao.photorate.sqldelight

fun interface Transaction {
  fun execute(block: () -> Unit)
}
