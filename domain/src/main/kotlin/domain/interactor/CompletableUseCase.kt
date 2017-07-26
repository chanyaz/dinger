package domain.interactor

import domain.DomainHolder
import domain.exec.PostExecutionSchedulerProvider
import io.reactivex.Completable
import io.reactivex.observers.DisposableCompletableObserver

abstract class CompletableUseCase(
        private val postExecutionSchedulerProvider: PostExecutionSchedulerProvider)
    : UseCase<Completable> {
    fun execute(subscriber: DisposableCompletableObserver) {
        buildUseCase()
                .subscribeOn(DomainHolder.useCaseScheduler)
                .observeOn(postExecutionSchedulerProvider.provideScheduler())
                .subscribeWith(subscriber)
    }
}
