import SwiftUI

struct NoMatchesCard: View {
    let query: String

    var body: some View {
        HStack {
            Text("No matches found")
        }
        .cornerRadius(24)
    }
}

#Preview {
    NoMatchesCard(query: "Preview Query")
}
