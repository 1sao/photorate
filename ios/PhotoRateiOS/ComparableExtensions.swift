//
//  ComparableExtensions.swift
//  PhotoRateiOS
//
//  Created by Ihor Pakharenko on 23. 9. 2026..
//

extension Comparable {
    func coercedIn(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
